package com.example.emulator.application.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartRequestDto {
    private String carNumber;
    private String startLatitude;
    private String startLongitude;
    private LocalDateTime startTime;
}
