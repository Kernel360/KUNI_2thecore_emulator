package com.example.emulator.application;

import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.car.exception.CarErrorCode;
import com.example.emulator.car.exception.CarNotFoundException;
import com.example.emulator.car.CarReader;
import com.example.emulator.controller.dto.LogPowerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


// emulatorId 삭제로 인한 디버깅 필요

@Service
@RequiredArgsConstructor
public class LogService {

    private final CarReader carReader;
//    private final EmulatorReader emulatorReader;
    private final GpxScheduler gpxScheduler;

    public LogPowerDto changePowerStatus(LogPowerDto logPowerDto) {
        String carNumber = logPowerDto.getCarNumber();
        String loginId = logPowerDto.getLoginId();
        String powerStatus = logPowerDto.getPowerStatus();

        CarEntity carEntity = carReader.findByCarNumber(carNumber)
                .orElseThrow(() -> new CarNotFoundException(CarErrorCode.CAR_NOT_FOUND_BY_NUMBER, carNumber));

        //       EmulatorEntity emulatorEntity = emulatorReader.getById(carEntity.getEmulatorId());
        //     emulatorEntity.setStatus(EmulatorStatus.getEmulatorStatus(powerStatus));

        if(powerStatus.equals("ON")) {
            // scheduler 시작
            gpxScheduler.setCarNumber(carNumber);
            gpxScheduler.setLoginId(loginId);

            gpxScheduler.init();
            gpxScheduler.startScheduler();
        }

        if(powerStatus.equals("OFF")) {
            gpxScheduler.stopScheduler();
        }

//        return LogPowerDto.builder()
//                .carNumber(emulatorEntity.getCarNumber())
//                .loginId(loginId)
//                .powerStatus(emulatorEntity.getStatus().toString())
//                .build();
    }
}
