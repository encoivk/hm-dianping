package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.IdUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService seckillVoucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IdUtils idUtils;
    @Resource
    RedissonClient redissonClient;
    IVoucherOrderService proxy;
    private BlockingQueue<VoucherOrder> blockingQueue =new  ArrayBlockingQueue<>(1024 * 1024);

    private final static ThreadPoolExecutor THREAD_POOL =new ThreadPoolExecutor(
            10,12,20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );  //线程池实现异步下单
    @PostConstruct
    private void init()
    {
        THREAD_POOL.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable  //下单逻辑
    {
        private String queueName="stream.orders";
        @Override
        public void run() {  //TODO 基于Redis的stream消息队列实现异步下单操作
            while (true)
            {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> readList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(readList==null||readList.isEmpty())
                    {
                        //没订单就继续尝试获取
                        continue;
                    }
                    MapRecord<String, Object, Object> mapRecord = readList.get(0);
                    Map<Object, Object> map = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.mapToBean(map, VoucherOrder.class, true);
                    proxy.addVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true)
            {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> mapRecord = list.get(0);
                    Map<Object, Object> map = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.mapToBean(map, VoucherOrder.class, true);
                    proxy.addVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", mapRecord.getId());

                } catch (Exception e) {
                    log.error("处理pendding订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

//    private class VoucherOrderHandler implements Runnable  //下单逻辑
//    {
//        @Override
//        public void run() {
//            try {
//                while (true) {
//                    VoucherOrder voucherOrder = blockingQueue.take();
//                    handleVoucherOrder(voucherOrder);
//                }
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) throws Exception
    {
        //TODO Redission再加锁(保险起见，不做也行，Lua脚本已经保证了操作的原子性)
        RLock redisLock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        boolean success = redisLock.tryLock(1L, TimeUnit.SECONDS);
        if(!success)
        {
            log.info("不允许重复下单");
            return;
        }
        try {
            //TODO 保证事务生效
            proxy.addVoucherOrder(voucherOrder);
        } finally {
            redisLock.unlock();
        }

    }
    private static final DefaultRedisScript<Long> JUDGE_SCRIPT;
    static {
        JUDGE_SCRIPT = new DefaultRedisScript<>();
        JUDGE_SCRIPT.setLocation(new ClassPathResource("judge.lua"));
        JUDGE_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //TODO spring的事务是放在threadLocal中，多线程事务会失效(此处获取代理对象)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        Long userId= UserHolder.getUser().getId();
        long orderId = idUtils.getId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                JUDGE_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        //判断结果是否为0
        if (r != 0) {
            //不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //TODO 放入阻塞队列
        VoucherOrder voucherOrder=VoucherOrder.builder()
                .voucherId(voucherId)
                .userId(userId)
                .id(orderId)
                .build();
        blockingQueue.add(voucherOrder);
        // 返回订单id
        return Result.success(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {  //秒杀优惠劵(乐观锁)
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        LocalDateTime now=LocalDateTime.now();
//        if(now.isBefore(voucher.getBeginTime()))
//        {
//            return Result.fail("秒杀未开始!!!");
//        }
//        if(now.isAfter(voucher.getEndTime()))
//        {
//            return Result.fail("秒杀已结束!!!");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<=0)
//        {
//            return Result.fail("库存不足!!!");
//        }
//        //获取userID
//        Long userId= UserHolder.getUser().getId();
//        //synchronized (userId.toString().intern())   //intern方法从常量池中拿到数据,保证同一个user都是同一把锁
////        LockUtils lockUtils=new RedisLockUtils(stringRedisTemplate);
////        boolean success=lockUtils.tryLock(RedisConstants.LOCK_SHOP_TTL,"seckill",userId.toString());
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean success=lock.tryLock(1L, TimeUnit.SECONDS);
//        if(success) {
//            try {
//                //this调用Spring的事务不会生效
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.addVoucherOrder(voucherId, userId);
//            } finally {
//                //lockUtils.unLock();
//                lock.unlock();
//            }
//        }
//        return Result.fail("无法重复下单!!!");
//    }

    @Transactional
    public Result addVoucherOrder(VoucherOrder order) {
        //一人一单
        Integer count = this.query().eq("voucher_id", order.getVoucherId())
                .eq("user_id", order.getUserId()).count();
        if(count!=0)
        {
            return Result.fail("无法重复下单!!!");
        }
        //更新优惠券数(MySQL设置stock大于0)
        boolean success=seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if(!success)
        {
            return Result.fail("库存不足!!!");
        }
        //添加订单信息表
        this.save(order);
        return Result.success(order.getVoucherId());
    }

}
