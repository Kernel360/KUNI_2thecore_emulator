package com.example.emulator.controller;

import com.example.emulator.application.LogService;
import com.example.emulator.application.dto.ApiResponse;
import com.example.emulator.controller.dto.LogPowerDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    // 애뮬레이터 시동 정보 로그
    @PostMapping("/power")
    public ApiResponse<LogPowerDto> powerLog(
            @Valid
            @RequestBody
            LogPowerDto logPowerDto
    ) {
        LogPowerDto response =  logService.changePowerStatus(logPowerDto);

        return ApiResponse.success( "시동 로그가 성공적으로 저장되었습니다.", response);
    }
}
