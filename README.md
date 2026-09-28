# SinBalSinGo

마인크래프트 PvP 서버용 채팅 신고 플러그인입니다. 신고가 들어오면 AI(OpenAI 또는 Gemini)가 최근 대화를 읽고 판단합니다. 확실하고 가벼운 위반은 바로 경고하거나 30분 채팅 금지하고 나머지는 운영자가 Discord에서 정합니다. 무거운 처벌은 자동으로 하지 않습니다.

## 설치

Paper 또는 Folia 1.20.1 이상, Java 17 이상이 필요합니다.

1. [Releases](https://github.com/irochi-moe/SinBalSinGo/releases)에서 `SinBalSinGo-<버전>.jar`를 받아 `plugins` 폴더에 넣습니다.
2. 서버를 켜면 `plugins/SinBalSinGo`에 설정 파일이 생깁니다. 처음 켤 때 Discord 라이브러리를 내려받으므로 인터넷 연결이 필요합니다.
3. `config.yml`에 AI 키와 Discord 설정을 넣고 재시작합니다. 항목 설명은 [config.yml](src/main/resources/config.yml) 주석에 있습니다.

## Discord 연결

1. Discord 개발자 포털에서 봇을 만들어 서버에 초대합니다.
2. 운영자 전용 채널에서 봇에게 채널 보기, 메시지 보내기, 메시지 기록 보기, 파일 첨부, 공개 스레드 만들기, 스레드에서 메시지 보내기 권한을 줍니다.
3. `config.yml`의 `discord`에 봇 토큰과 서버·채널·운영자 역할 ID를 넣습니다.

신고마다 카드가 올라오고 카드의 스레드에 채팅 원문 파일이 붙습니다. AI가 추천한 처벌은 빨간 버튼이고 다른 처벌도 고를 수 있습니다. 위반이 아니면 `문제 없음`을 누릅니다. 애매하면 누르지 말고 스레드에서 논의하세요. 누르기 전에는 처벌하지 않습니다. `(자동)` 표시가 있으면 일부는 이미 처벌된 것이고 버튼은 더할 처벌만 정합니다.

## 자동 처벌

R01 욕설, R02 금지 표현 우회, R04 가족 모욕, R10 종결어미 ~노만 자동 처벌합니다. AI가 확신도 95 이상의 확실한 위반으로 보고 다른 해석이나 예외가 없어야 하며 추천된 처벌이 `automatic: true`여야 합니다. 한 신고에 애매한 판단이 섞여 있으면 확실한 부분만 먼저 처벌하고 나머지는 운영자가 정합니다.

끄려면 `moderation.automatic-enforcement: false`로 바꾸세요. 규칙별로는 `moderation.rules`의 `automatic-eligible`과 `confidence`로 조정합니다.

## 처벌 설정

`moderation.actions`에 가벼운 처벌부터 적습니다. 기본값은 [LiteBans](https://www.spigotmc.org/resources/litebans.3715/)의 경고, 30분 채팅 금지, 1일 채팅 금지입니다. AI가 매긴 심각도가 `min-severity` 이상인 처벌 중 가장 무거운 것을 추천합니다. 명령어에는 `{target}`(닉네임), `{uuid}`, `{reason}`(위반한 규칙)을 쓸 수 있습니다.

이전 위반 횟수는 반영하지 않으니 누적 처벌은 LiteBans 경고 누적을 쓰세요. 처벌받은 플레이어에게는 이 플러그인이 메시지를 보내지 않습니다.

## 대화 규칙

- 욕설, 우회 욕설, 인신공격, 가족 모욕, 차별, 성희롱, 반복 괴롭힘, 고인 조롱은 친구 사이나 농담이어도 금지합니다. 가벼운 경기 놀림은 허용합니다.
- 종결어미 `~노`는 사투리여도 금지합니다. `응디`, `부엉이바위` 같은 표현과 지정된 정치인 이름도 금지합니다.
- 신고나 규칙 질문에 필요한 인용은 허용합니다.

전체 규칙과 예시는 [policy.json](src/main/resources/policy.json)에 있습니다.

## 명령어와 권한

| 명령어 | 하는 일 |
| --- | --- |
| `/report <닉네임> [카테고리] [사유]` | 신고 |
| `/sinbalsingo status` | 설정 상태 확인 |
| `/sinbalsingo reload` | 설정 다시 읽기 (Discord·처벌 설정은 재시작 필요) |
| `/sinbalsingo retry` | 장애가 풀린 뒤 AI 심사와 Discord 전송 다시 시도 |
| `/sinbalsingo export` | 검토 결과를 `evaluation.jsonl`로 저장 |

| 권한 | 하는 일 | 기본 |
| --- | --- | --- |
| `irochi.sinbalsingo.use` | `/report` 명령어 사용 | 모두 |
| `irochi.sinbalsingo.admin` | `/sinbalsingo` 명령어 사용 | op |
| `irochi.sinbalsingo.notify` | 신고 진행 알림 받기 | op |
| `irochi.sinbalsingo.cooldown.bypass` | 신고 쿨타임 무시 | op |

신고는 한 번 하면 60초 동안 다시 할 수 없습니다. `report-cooldown-seconds`로 바꿉니다.

## 그 밖에

- 신고는 `plugins/SinBalSinGo/cases`에 저장되어 재시작이나 장애 뒤에도 이어서 처리되고 14일이 지나면 지워집니다. 대화 내용이 들어 있으니 운영자만 볼 수 있게 하세요.
- 처벌 도중 서버가 꺼지면 중복 처벌을 막으려고 다시 실행하지 않습니다. 제재 플러그인 기록을 확인하세요.
- 처음 몇 주는 자동 처벌이 맞았는지 카드로 확인하세요. 실제 AI·Discord 연결은 아직 검증하지 않았습니다.
- 메시지는 플레이어의 게임 언어에 맞춰 한국어나 영어로 나갑니다. 문구는 `lang/<언어코드>.yml`에서 고치고 파일을 추가하면 다른 언어도 쓸 수 있습니다. Discord 카드 언어는 `discord.language`로 정합니다. AI 판단 내용은 항상 한국어입니다.
- [GuRoYeokSiBal](https://github.com/irochi-moe/GuRoYeokSiBal)을 함께 쓰면 욕설 필터가 적용되는 채널의 채팅만 기록합니다. 필터에 막힌 채팅은 막혔다는 표시와 함께 AI에게 넘기고 쿨타임에 막힌 채팅은 기록하지 않습니다.
- 직접 빌드하려면 `./gradlew build`를 실행하세요. 결과물은 `build/libs`에 생깁니다.

## 라이선스

이 프로젝트는 [GPL v3](LICENSE.md) 라이선스를 따릅니다.
