const messages:Record<string,string> = {
  "허용 계정 설정을 확인해 주세요.": "Check the allowed account configuration.",
  "저장소 목록 크기 제한을 확인해 주세요.": "The repository inventory exceeds its size limit.",
  "허용 계정의 공개 저장소 목록만 사용할 수 있습니다.": "Only public repositories belonging to allowed accounts are supported.",
  "공개 저장소를 찾을 수 없습니다.": "Public repository not found.",
  "GitHub 응답 크기 제한을 초과했습니다.": "The GitHub response exceeds the size limit.",
  "저장된 설정 크기를 확인해 주세요.": "The stored configuration exceeds the size limit.",
  "저장소 연결 설정을 확인해 주세요.": "Check the repository link configuration.",
  "저장소 비활성 설정을 확인해 주세요.": "Check the disabled repository configuration.",
  "설정 크기 제한을 초과했습니다.": "The configuration exceeds the size limit.",
  "설정이 다른 작업에서 변경되었습니다. 관리자 복구가 필요합니다.": "The configuration changed during another operation. Admin recovery is required.",
  "JSON 요청이 필요합니다.": "A JSON request is required.",
  "요청 내용이 없습니다.": "The request body is missing.",
  "요청 크기 제한을 초과했습니다.": "The request exceeds the size limit.",
  "올바른 JSON 요청이 필요합니다.": "A valid JSON request is required.",
  "GET 요청만 지원합니다.": "Only GET requests are supported.",
  "관리자 인증 설정이 필요합니다.": "Admin authentication must be configured.",
  "관리자 인증이 필요합니다.": "Admin authentication is required.",
  "GET/PUT 요청만 지원합니다.": "Only GET and PUT requests are supported.",
  "동기화 스냅샷이 필요합니다.": "A synchronization snapshot is required.",
  "다른 작업이 진행 중입니다.": "Another operation is in progress.",
  "스냅샷이 변경되었습니다. 다시 조회하고 동기화해 주세요.": "The snapshot changed. Fetch a new snapshot before syncing.",
  "연동 상태 또는 앱 서명·메타데이터 검증에 실패했습니다.": "Link state, signing certificate, or app metadata validation failed.",
  "계정당 저장소 1,000개 제한을 초과했습니다.": "The limit of 1,000 repositories per account was exceeded.",
  "허용된 저장소와 변경할 연결·비활성 여부를 지정해 주세요.": "Specify an allowed repository and the link or disabled state to change.",
  "동기화 또는 다른 관리자 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.": "A sync or admin operation is in progress. Try again shortly.",
  "메타데이터 저장소 계정 설정을 확인해 주세요.": "Check the metadata store account configuration.",
  "허용 계정의 공개 저장소만 연결할 수 있습니다.": "Only public repositories belonging to allowed accounts can be linked.",
  "연결 가능한 앱 저장소는 최대 100개입니다.": "At most 100 app repositories can be linked.",
  "비활성 저장소 제한을 초과했습니다.": "The disabled repository limit was exceeded.",
  "카탈로그 복구가 필요합니다.": "Catalog recovery is required.",
  "비활성화했습니다. 연동 선택은 유지되며 두 기본 목록에서 숨깁니다.": "Disabled and hidden from both default lists. The link is preserved.",
  "설정을 저장하고 동기화를 요청했습니다.": "Settings saved and sync requested.",
  "설정을 저장했습니다. 동기화는 설정 후 수동 실행하거나 다음 예약 실행에서 진행됩니다.": "Settings saved. Run sync manually or wait for the next scheduled run.",
  "연결을 해제하고 공개 목록에서 제거했습니다.": "Unlinked and removed from the public catalog.",
  "관리자 작업을 완료하지 못했습니다. 저장 상태와 동기화 잠금을 확인해 주세요.": "The admin operation could not be completed. Check the saved state and sync lock."
};

export function adminMessage(message:string,language:string):string {
  if(language.toLowerCase().startsWith('ko'))return message;
  const upstream=/^GitHub 저장소 조회에 실패했습니다 \((\d+)\)\.$/.exec(message);
  return messages[message] ?? (upstream ? `GitHub repository lookup failed (${upstream[1]}).` : message);
}
