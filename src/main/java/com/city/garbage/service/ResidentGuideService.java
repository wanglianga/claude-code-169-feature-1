package com.city.garbage.service;

import com.city.garbage.domain.Community;
import com.city.garbage.domain.GarbageRoom;
import com.city.garbage.repo.CommunityRepository;
import com.city.garbage.repo.GarbageRoomRepository;
import com.city.garbage.support.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 居民端投放引导：封桶期间展示封桶状态、预计恢复时间和最近可用投放点。
 * 备用点优先同小区、再同街道，按经纬度直线距离排序；无坐标时退化为同小区优先。
 */
@Service
@RequiredArgsConstructor
public class ResidentGuideService {

    private final GarbageRoomRepository roomRepository;
    private final CommunityRepository communityRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> disposalGuide(String roomCode) {
        GarbageRoom room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        Community community = communityRepository.findById(room.getCommunityId()).orElse(null);

        List<Map<String, Object>> alternatives = roomRepository.findAll().stream()
                .filter(r -> !r.getCode().equals(roomCode))
                .filter(r -> !r.isSealed() && !r.isKitchenFull())
                .map(r -> toAlternative(room, r, community))
                .sorted(Comparator
                        .comparingInt((Map<String, Object> m) -> Boolean.TRUE.equals(m.get("sameCommunity")) ? 0 : 1)
                        .thenComparing(m -> (Number) m.get("distanceM"),
                                Comparator.nullsLast(Comparator.comparingDouble(Number::doubleValue))))
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roomCode", room.getCode());
        view.put("roomName", room.getName());
        view.put("address", room.getAddress());
        view.put("kitchenFull", room.isKitchenFull());
        view.put("kitchenCapacityPct", room.getKitchenCapacityPct());
        view.put("sealed", room.isSealed());
        view.put("sealedAt", room.getSealedAt());
        view.put("estimatedResumeAt", room.getEstimatedResumeAt());
        view.put("activeCaseNo", room.getActiveCaseNo());
        if (room.isSealed()) {
            Map<String, Object> nearest = alternatives.isEmpty() ? null : alternatives.get(0);
            view.put("notice", "本点厨余桶已满溢临时封桶，暂勿在此投放；请前往最近备用点"
                    + (nearest != null ? "「" + nearest.get("roomName") + "」" : "（备用点稍后更新）")
                    + "，预计 " + room.getEstimatedResumeAt() + " 恢复");
        } else {
            view.put("notice", "本点可正常投放厨余垃圾");
        }
        view.put("alternatives", alternatives);
        return view;
    }

    private Map<String, Object> toAlternative(GarbageRoom from, GarbageRoom r, Community fromCommunity) {
        Community c = communityRepository.findById(r.getCommunityId()).orElse(null);
        boolean sameCommunity = c != null && fromCommunity != null
                && c.getId().equals(fromCommunity.getId());
        boolean sameStreet = c != null && fromCommunity != null
                && c.getStreetName() != null && c.getStreetName().equals(fromCommunity.getStreetName());
        Double distance = (from.getLat() != null && from.getLng() != null && r.getLat() != null && r.getLng() != null)
                ? haversine(from.getLat(), from.getLng(), r.getLat(), r.getLng()) : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomCode", r.getCode());
        m.put("roomName", r.getName());
        m.put("address", r.getAddress());
        m.put("communityName", c == null ? "" : c.getName());
        m.put("streetName", c == null ? "" : c.getStreetName());
        m.put("sameCommunity", sameCommunity);
        m.put("sameStreet", sameStreet);
        m.put("distanceM", distance == null ? null : Math.round(distance));
        m.put("kitchenFull", r.isKitchenFull());
        m.put("sealed", r.isSealed());
        m.put("available", !r.isSealed() && !r.isKitchenFull());
        return m;
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
