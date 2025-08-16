package com.example.emulator.car.domain;

import com.example.emulator.car.CarStatus;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Entity(name = "Car")
@Table(name = "car")

public class CarEntity {
    @Id
    @Column(name = "car_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id; // PK

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private CarStatus status; // 차량 상태

    @Column(name = "car_number")
    private String carNumber; // 차량 번호

    @Column(name="login_id")
    private String loginId;

}
