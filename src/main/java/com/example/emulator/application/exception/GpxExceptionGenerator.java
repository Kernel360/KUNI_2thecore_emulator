package com.example.emulator.application.exception;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GpxExceptionGenerator {

    /**
     * GPX 데이터 리스트에 '신호 끊김(프리즈)' 예외 상황을 시뮬레이션
     * @param originalLines 원본 GPX 파일 라인 리스트
     * @param freezeStartIndex 프리즈를 시작할 인덱스
     * @param freezeDuration 프리즈 지속 시간 (라인 수)
     * @return 프리즈가 적용된 새로운 라인 리스트
     */
    public static List<String> introduceFreeze(List<String> originalLines, int freezeStartIndex, int freezeDuration){
        if (originalLines == null || originalLines.isEmpty() || freezeStartIndex < 0 || freezeStartIndex >= originalLines.size()){
            return originalLines;
        }

        log.info("신호 끊김(프리즈) 시뮬레이션 적용. 시작 인덱스: {}, 지속 시간: {}", freezeStartIndex, freezeDuration);
        List<String> modifiedList = new ArrayList<>(originalLines);
        String freezeLine = modifiedList.get(freezeStartIndex); // 고정될 좌표 라인

        int end = Math.min(freezeStartIndex + freezeDuration, modifiedList.size());
        for(int i = freezeStartIndex + 1; i < end; i++) {
            modifiedList.set(i, freezeLine); // 지정된 시간 동안 동일한 좌표 라인으로 덮어쓰기
        }
        return modifiedList;
    }

    /**
     * GPX 파일 라인 리스트에 '신호 끊김(삭제)' 예외 상황을 시뮬레이션
     * @param originalLines 원본 GPX 파일 라인 리스트
     * @param deletionStartIndex 삭제를 시작할 인덱스
     * @param deletionCount 삭제할 라인 수
     * @return 일부 라인이 삭제된 새로운 리스트
     */
    public static List<String> introduceDeletion(List<String> originalLines, int deletionStartIndex, int deletionCount) {
        if (originalLines == null || originalLines.isEmpty() || deletionStartIndex < 0 || deletionCount <= 0) {
            return originalLines;
        }
        log.info("신호 끊김(삭제) 시뮬레이션 적용. 시작 인덱스: {}, 삭제 개수: {}", deletionStartIndex, deletionCount);

        List<String> modifiedList = new ArrayList<>();
        for (int i = 0; i < originalLines.size(); i++) {
            if (i >= deletionStartIndex && i < deletionStartIndex + deletionCount) {
                // 이 구간의 데이터를 건너뛰어 리스트에 추가하지 않음
                continue;
            }
            modifiedList.add(originalLines.get(i));
        }
        return modifiedList;
    }
}
