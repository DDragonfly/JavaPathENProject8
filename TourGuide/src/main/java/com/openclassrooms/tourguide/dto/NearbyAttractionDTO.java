package com.openclassrooms.tourguide.dto;

public record NearbyAttractionDTO (
        String attractionName,
        double attractionLatitude,
        double attractionLongitude,
        double userLatitude,
        double userLongitude,
        double distanceMiles,
        int rewardPoints
) {}

