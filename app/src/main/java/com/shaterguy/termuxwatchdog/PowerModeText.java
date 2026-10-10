package com.shaterguy.termuxwatchdog;

final class PowerModeText {
    static String error(String code, boolean recoveryBlocked) {
        switch (code) {
            case "MODE_BACKEND_NOT_READY": return "절전 연동이 아직 준비되지 않았습니다. 상태 확인이 필요합니다.";
            case "TERMUX_PERMISSION_REQUIRED": return "기존 Termux 명령 실행 권한이 필요합니다.";
            case "TERMUX_COMMAND_UNAVAILABLE":
            case "MODE_COMMAND_FAILED": return "Termux 연결 또는 절전모드 도우미를 확인하지 못했습니다.";
            case "TOPOLOGY_UNVERIFIED": return "서버 구성을 확인하지 못해 전환을 보류했습니다.";
            case "MODE_RESPONSE_INVALID":
            case "MODE_RESPONSE_TRUNCATED": return "상태 응답을 확인하지 못했습니다."
                    + (recoveryBlocked ? " 자동복구 잠금을 유지합니다." : "");
            case "MODE_RESULT_TIMEOUT": return "응답을 기다리고 있습니다. 중복 전환 없이 상태를 다시 확인합니다.";
            default: return "전환 상태를 확인하지 못했습니다. 잠시 후 다시 확인하세요.";
        }
    }
}
