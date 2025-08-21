package com.example.emulator.application.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EndRequestDto {
    private String carNumber;
    private LocalDateTime startTime;

    private String endLatitude;
    private String endLongitude;
    private LocalDateTime endTime;
}
