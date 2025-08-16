package com.example.emulator.controller;

import com.example.emulator.application.CarListService;
import com.example.emulator.application.dto.ApiResponse;
import com.example.emulator.car.domain.CarEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/list")
public class CarListController {

    private final CarListService carListService;

    @GetMapping()
    public ApiResponse<List<CarEntity>> getCarList(
            @RequestParam("loginId") String loginId
    ){
        List<CarEntity> carEntityList= carListService.getCarList(loginId);
        log.info(carEntityList.toString());
        return ApiResponse.success(carEntityList);
    }

}
