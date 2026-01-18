package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

/**
 * Service responsible for calculating user rewards based on proximity
 * to attractions.
 *
 * <p>
 * This service is optimized for high-volume scenarios and supports
 * parallel execution using an ExecutorService.
 * </p>
 */
@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
    private final ExecutorService rewardsExecutor = Executors.newFixedThreadPool(Math.min(64, Math.max(16, Runtime.getRuntime().availableProcessors() * 8)));

    private final List<Attraction> cachedAttractions;

    // proximity in miles
    private int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;
    private int attractionProximityRange = 200;

    private final RewardCentral rewardsCentral;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.rewardsCentral = rewardCentral;
        this.cachedAttractions = List.copyOf(gpsUtil.getAttractions());
    }


    public int getAttractionRewardPoints(Attraction attraction, User user) {
        return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
    }

    public void setProximityBuffer(int proximityBuffer) {
        this.proximityBuffer = proximityBuffer;
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }

    /**
     * Calculates rewards for a single user.
     *
     * <p>
     * The method:
     * <ul>
     *   <li>Takes a snapshot of visited locations</li>
     *   <li>Finds nearby attractions</li>
     *   <li>Computes reward points outside synchronized blocks</li>
     *   <li>Adds rewards in a thread-safe manner</li>
     * </ul>
     * </p>
     *
     * @param user the user for whom rewards are calculated
     */
    public void calculateRewards(User user) {
        // SNAPSHOT
        List<VisitedLocation> userLocations;
        Set<String> rewardedAttractions;

        synchronized (user) {
            userLocations = new ArrayList<>(user.getVisitedLocations());
            rewardedAttractions = user.getUserRewards().stream()
                    .map(r -> r.attraction.attractionName)
                    .collect(Collectors.toSet());
        }

        List<PendingReward> pending = new ArrayList<>();
        Set<String> newlyAddedNames = new java.util.HashSet<>();


        for (VisitedLocation visitedLocation : userLocations) {
            for (Attraction attraction : cachedAttractions) {

                String name = attraction.attractionName;
                if (rewardedAttractions.contains(name) || newlyAddedNames.contains(name)) {
                    continue;
                }

                if (nearAttraction(visitedLocation, attraction)) {
                    pending.add(new PendingReward(visitedLocation, attraction));
                    newlyAddedNames.add(name);
                }
            }
        }
        if (pending.isEmpty()) return;

// calcolo punti fuori lock
        List<UserReward> rewardsToAdd = pending.stream()
                .map(pr -> new UserReward(
                        pr.visitedLocation(), pr.attraction(), getAttractionRewardPoints(pr.attraction(), user)
                ))
                .toList();

        synchronized (user) {
            Set<String> finalRewarded = user.getUserRewards().stream()
                    .map(r -> r.attraction.attractionName)
                    .collect(Collectors.toSet());

            for (UserReward r : rewardsToAdd) {
                if (!finalRewarded.contains(r.attraction.attractionName)) {
                    user.addUserReward(r);
                    finalRewarded.add(r.attraction.attractionName);
                }
            }
        }
    }

    private record PendingReward(VisitedLocation visitedLocation, Attraction attraction) {}

    /**
     * Calculates rewards for a list of users in parallel.
     *
     * <p>
     * Uses an ExecutorService to improve performance when processing
     * a large number of users.
     * </p>
     *
     * @param users list of users to process
     */
    public void calculateRewardsForUsers(List<User> users) {
        List<CompletableFuture<Void>> futures = users.stream()
                .map(u -> CompletableFuture.runAsync(() -> calculateRewards(u), rewardsExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }


    public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
        return getDistance(attraction, location) <= attractionProximityRange;
    }

    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
    }



    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
    }

}
