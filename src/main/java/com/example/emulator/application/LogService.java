package com.example.emulator.application;

import com.example.emulator.car.CarStatus;
import com.example.emulator.car.domain.CarEntity;
import com.example.emulator.car.exception.CarErrorCode;
import com.example.emulator.car.exception.CarNotFoundException;
import com.example.emulator.car.CarReader;
import com.example.emulator.controller.dto.LogPowerDto;
import com.example.emulator.infrastructure.car.CarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


// emulatorId 삭제로 인한 디버깅 필요

@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final CarReader carReader;
    private final CarRepository carRepository;
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

            //status 변경 "운행"
            log.info("emul status on: {} ", carNumber);
            carEntity.setStatus(CarStatus.DRIVING);
            carRepository.save(carEntity);
            // scheduler 시작
            log.info("emul running");
            gpxScheduler.setCarNumber(carNumber);
            gpxScheduler.setLoginId(loginId);

            gpxScheduler.init();
        }

        if(powerStatus.equals("OFF")) {
            //status 변경 "대기"
            log.info("emul status off: {}",carNumber);
            carEntity.setStatus(CarStatus.IDLE);
            carRepository.save(carEntity);
            gpxScheduler.stopScheduler();
        }

        return LogPowerDto.builder()
                .carNumber(carEntity.getCarNumber())
                .loginId(loginId)
                .powerStatus(carEntity.getStatus().getDisplayName())
                .build();
    }
}

