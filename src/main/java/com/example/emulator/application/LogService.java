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
            gpxScheduler.startGpxSimulation(carNumber, loginId);
        }

        if(powerStatus.equals("OFF")) {
            //status 변경 "대기"
            log.info("emul status off: {}",carNumber);
            carEntity.setStatus(CarStatus.IDLE);
            carRepository.save(carEntity);
            gpxScheduler.stopGpxSimulation(carNumber);
        }

        return LogPowerDto.builder()
                .carNumber(carEntity.getCarNumber())
                .loginId(loginId)
                .powerStatus(carEntity.getStatus().getDisplayName())
                .build();
    }
}





//package com.example.emulator.application;
//
//import com.example.emulator.car.CarStatus;
//import com.example.emulator.car.domain.CarEntity;
//import com.example.emulator.car.exception.CarErrorCode;
//import com.example.emulator.car.exception.CarNotFoundException;
//import com.example.emulator.car.CarReader;
//import com.example.emulator.controller.dto.LogPowerDto;
//import com.example.emulator.infrastructure.car.CarRepository;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class LogService {
//
//    private final CarReader carReader;
//    private final CarRepository carRepository;
//    private final RestTemplate restTemplate;
//    private final Map<String, GpxScheduler> schedulers = new ConcurrentHashMap<>();
//
//    public LogPowerDto changePowerStatus(LogPowerDto logPowerDto) {
//        String carNumber = logPowerDto.getCarNumber();
//        String loginId = logPowerDto.getLoginId();
//        String powerStatus = logPowerDto.getPowerStatus();
//
//        CarEntity carEntity = carReader.findByCarNumber(carNumber)
//                .orElseThrow(() -> new CarNotFoundException(CarErrorCode.CAR_NOT_FOUND_BY_NUMBER, carNumber));
//
//        if (powerStatus.equals("ON")) {
//            // 이미 실행 중인 스케줄러가 있으면 중지
//            if (schedulers.containsKey(carNumber)) {
//                log.info("이미 실행중인 스케줄러가 있어, 기존 스케줄러를 중지합니다: {}", carNumber);
//                schedulers.get(carNumber).stopScheduler();
//                schedulers.remove(carNumber);
//            }
//
//            //status 변경 "운행"
//            log.info("차량 상태 ON: {} ", carNumber);
//            carEntity.setStatus(CarStatus.DRIVING);
//            carRepository.save(carEntity);
//
//            // 새 스케줄러 생성 및 시작
//            log.info("새로운 스케줄러를 시작합니다: {}", carNumber);
//            GpxScheduler gpxScheduler = new GpxScheduler(restTemplate);
//            schedulers.put(carNumber, gpxScheduler);
//            gpxScheduler.init(carNumber, loginId);
//
//        } else if (powerStatus.equals("OFF")) {
//            //status 변경 "대기"
//            log.info("차량 상태 OFF: {}", carNumber);
//            carEntity.setStatus(CarStatus.IDLE);
//            carRepository.save(carEntity);
//
//            // 스케줄러 중지
//            if (schedulers.containsKey(carNumber)) {
//                log.info("실행중인 스케줄러를 중지합니다: {}", carNumber);
//                schedulers.get(carNumber).stopScheduler();
//                schedulers.remove(carNumber);
//            } else {
//                log.warn("중지할 스케줄러를 찾을 수 없습니다: {}", carNumber);
//            }
//        }
//
//        return LogPowerDto.builder()
//                .carNumber(carEntity.getCarNumber())
//                .loginId(loginId)
//                .powerStatus(carEntity.getStatus().getDisplayName())
//                .build();
//    }
//}