package com.example.emulator.application.dto;


import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DriveLogEventDto {

    private String carNumber;

    private String status;

    private LocalDateTime eventTime;

}
