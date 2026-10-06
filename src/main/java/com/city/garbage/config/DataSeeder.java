package com.city.garbage.config;

import com.city.garbage.domain.Community;
import com.city.garbage.domain.GarbageRoom;
import com.city.garbage.domain.Resident;
import com.city.garbage.repo.CommunityRepository;
import com.city.garbage.repo.GarbageRoomRepository;
import com.city.garbage.repo.ResidentRepository;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 演示/验证用种子数据：街道、小区、垃圾房、居民 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSeeder {

    @Bean
    public ApplicationRunner seed(CommunityRepository communityRepository,
                                  GarbageRoomRepository roomRepository,
                                  ResidentRepository residentRepository,
                                  TimeService timeService) {
        return args -> {
            if (communityRepository.count() > 0) return;

            Community c1 = communityRepository.save(new Community("C001", "阳光花园", "滨江街道"));
            Community c2 = communityRepository.save(new Community("C002", "翠柳小区", "滨江街道"));

            GarbageRoom r1 = newRoom("ROOM1", "阳光花园1号垃圾房", c1.getId(), "阳光花园南门", timeService.now());
            GarbageRoom r2 = newRoom("ROOM2", "阳光花园2号垃圾房", c1.getId(), "12栋北侧", timeService.now());
            GarbageRoom r3 = newRoom("ROOM3", "翠柳小区1号垃圾房", c2.getId(), "翠柳小区东门", timeService.now());
            // 演示坐标（杭州一带近似经纬度），供封桶后最近备用点按距离排序
            r1.setLng(120.1812); r1.setLat(30.2741);
            r2.setLng(120.1856); r2.setLat(30.2768);
            r3.setLng(120.1963); r3.setLat(30.2810);
            roomRepository.save(r1);
            roomRepository.save(r2);
            roomRepository.save(r3);

            Resident p1 = residentRepository.save(new Resident("CARD1001", "王建国", c1.getId(), "12栋1单元"));
            Resident p2 = residentRepository.save(new Resident("CARD1002", "李秀兰", c1.getId(), "12栋2单元"));
            Resident p3 = residentRepository.save(new Resident("CARD1003", "张伟",   c2.getId(), "3栋1单元"));
            // 初始积分，便于演示错投扣分与申诉退还
            p1.setTotalPoints(100); p2.setTotalPoints(100); p3.setTotalPoints(100);
            residentRepository.saveAll(java.util.List.of(p1, p2, p3));

            log.info("种子数据已写入：2个小区、3个垃圾房、3位居民");
        };
    }

    private GarbageRoom newRoom(String code, String name, Long communityId, String address,
                                java.time.OffsetDateTime now) {
        GarbageRoom r = new GarbageRoom();
        r.setCode(code);
        r.setName(name);
        r.setCommunityId(communityId);
        r.setAddress(address);
        r.setOnline(true);
        r.setLastHeartbeatAt(now);
        return r;
    }
}
