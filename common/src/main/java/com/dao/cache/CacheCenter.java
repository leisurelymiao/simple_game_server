package com.dao.cache;

import com.entry.BaseEntry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.util.StringUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
@EnableScheduling
@Slf4j
public class CacheCenter{
	
	@Autowired
	private MongoTemplate mongoTemplate;

    private final Map<String, BaseEntry> cacheMap = new HashMap<>();

    private Executor saveThread = Executors.newSingleThreadExecutor();

	@Getter
	public final Object lock = new Object();

    private String makeKey(BaseEntry baseEntry) {
        return baseEntry.getClass().asSubclass(BaseEntry.class).getSimpleName() + ":" + baseEntry.getId();
    }

    private String makeKey(Class<BaseEntry> baseEntryClass, long id) {
        return baseEntryClass.asSubclass(BaseEntry.class).getSimpleName() + ":" + id;
    }

	public void add(BaseEntry baseEntry){
		synchronized(lock){
            cacheMap.put(makeKey(baseEntry), baseEntry);
		}
	}

    /**
     * 当delete发生时，清空队列里的同id数据，防止delete后落地了脏数据
     */
    public void clearWhenDelete(Class<BaseEntry> type, long id) {
        synchronized (lock) {
            cacheMap.remove(makeKey(type, id));
        }
    }

    /**
     * 清除一个Player所有缓存
     */
    public void clearWhenDeleteAll(long id){
		synchronized(lock) {
            for (String key : cacheMap.keySet()) {
                if (StringUtil.getSpliteSuffix(key, ":").equals(String.valueOf(id))) {
                    cacheMap.remove(key);
                }
			}
		}
	}
	
	
	
	@Scheduled(fixedDelay=1000*5)// 5秒执行一次
	public void batchSave(){

		synchronized(lock) {

            List<BaseEntry> list = Lists.newArrayList(cacheMap.values());
			
			//按class分组，因为mongo的批量只支持单集合批量
			Map<Class,List<BaseEntry>> collect=list.stream()
					.collect(Collectors.groupingBy(x->x.getClass().asSubclass(BaseEntry.class)));
			
			log.info("共有 {} 个集合等待更新",collect.size());
            //开始保存
            saveThread.execute(() -> {
                StopWatch stopWatch = new StopWatch();
                for (Map.Entry<Class, List<BaseEntry>> entry : collect.entrySet()) {
                    stopWatch.reset();
                    stopWatch.start();
                    log.info("开始执行 {}  一共有 {} 条", entry.getKey(), entry.getValue().size());
                    BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, entry.getKey());

                    for (BaseEntry baseEntry : entry.getValue()) {
                        Map<String, Object> updateMap = null;
                        ObjectMapper objectMapper = new ObjectMapper();
                        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

                        try {
                            String json = objectMapper.writeValueAsString(baseEntry);
                            updateMap = objectMapper.readValue(json, Map.class);
                        } catch (IOException e) {
                            log.error("批量入库，转码报错 {} -> {}", entry.getKey(), baseEntry.getId(), e);
                        }
                        Update update = new Update();
                        if (Objects.isNull(updateMap)) {
                            continue;
                        }
                        for (Map.Entry<String, Object> stringObjectEntry : updateMap.entrySet()) {
                            if (stringObjectEntry.getKey().equals("id")) {
                                continue;
                            }
                            update.set(stringObjectEntry.getKey(), stringObjectEntry.getValue());
                        }

                        ops.upsert(new Query(Criteria.where("id").is(baseEntry.getId())), update);
                    }
                    ops.execute();
                    stopWatch.stop();
                    log.info("结束执行 {}  ，用时 {} 毫秒", entry.getKey(), stopWatch.getTime());
                }
            });
		}
	}
}
