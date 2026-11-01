# S-Com 측정 결과

측정일: 2026-11-02 · 브랜치: `measure/concurrency-nplus1` · 원본 데이터: [`raw/`](raw/)

## 요약 표

| 측정 항목 | 값 | 측정 조건 | 이력서에 쓸 한 줄 |
|---|---|---|---|
| 초과 판매(재고 불일치) | **0건** (측정 5회 모두) | 재고 R=100, 동시 주문 N=200, 스레드 50, 최대 시도 3회, 측정 5회(워밍업 1회 제외) | `@Version` 낙관적 락으로 재고 100개에 동시 주문 200건을 넣은 테스트에서 초과 판매 0건 (5회 반복) |
| 성공 주문 수(재시도 있음) | **52건** (5회 모두 52건) | 위와 같음 | 낙관적 락 충돌 재시도(최대 3회)를 넣어, 같은 경합 조건에서 주문 성공이 20건에서 52건으로 2.6배 늘어남 |
| 성공 주문 수(재시도 없음) | **20건** (5회 모두 20건) | 위와 같고 최대 시도만 1회 | (위 줄의 비교 기준) |
| 재시도 후 성공률 | 판매 가능 재고 대비 **52%** (52/100), 요청 대비 **26%** (52/200) | 위와 같음 | 쓰지 않는 것을 권장 (아래 "해석" 참고) |
| 최종 재고 | 재시도 3회: **48**, 재시도 없음: **80** | 위와 같음 | – |
| 실패 원인 | 재시도 3회: 시도 소진 148건, 재고 부족 0건, 기타 0건. 재시도 없음: 시도 소진 180건 | 위와 같음 | – |
| 보상 환불 호출(Stripe 목) | 실패 주문 수와 같음 (148건, 180건) | 위와 같음, `StripeService`는 Mockito 목 | 주문 실패 시 결제 환불을 보상 트랜잭션으로 자동 호출 (실패 주문 수 = 환불 호출 수) |
| 낙관적 락 충돌 수 | 재시도 3회: 회당 평균 464회 (460~467회). 재시도 없음: 180회 | Hibernate Statistics `optimisticFailureCount` | – |
| 평균 응답 시간 | 재시도 3회: **1,225.45ms** (p95 2,052ms). 재시도 없음: **467.15ms** (p95 643ms) | 서비스 메서드를 직접 호출한 시간(HTTP·JWT 제외), 로컬 Docker, Hikari 풀 10 | 쓰지 않는 것을 권장 (로컬 환경 값이고, 재시도 때문에 늘어난 수치임) |
| 장바구니 조회 쿼리 수 (JOIN FETCH만 적용) | **14 → 14** (변화 없음) | 장바구니 1개, 상품 10개, `CartService.getCart`, 읽기 전용 트랜잭션, 3회 반복 | 쓰지 않음 (효과 없음) |
| 장바구니 조회 쿼리 수 (`Product.products`를 LAZY로 변경) | **14 → 4** (−71%) | 위와 같음 | 장바구니(상품 10개) 조회에서 EAGER 컬렉션이 일으키던 N+1을 LAZY 전환으로 없애 쿼리를 14회에서 4회로 줄임 |
| 상품 목록 조회 쿼리 수 (LAZY로 변경) | **15 → 4** (−73%) | 상품 10개 페이지, `ProductService.getAllProducts`, 3회 반복 | 상품 목록(10개) 조회 쿼리를 15회에서 4회로 줄임 |
| HTTP 레벨 응답 시간 | **미측정** | – | 서비스를 직접 호출했기 때문에 JWT 필터, 직렬화, 네트워크 시간은 포함되지 않음 |
| 운영 환경(EB + RDS) 수치 | **미측정** | – | 로컬 Docker PostgreSQL에서만 측정함 |
| 분산 락과의 비교 | **미측정** | – | 레포에 분산 락 코드가 없음 (`LOCK_VERIFICATION.md` 참고) |
| 주문별 시도 횟수 분포 | **미측정** | – | 전체 충돌 횟수만 집계했고, 주문 단위 계측은 넣지 않음 |

## 해석

1. **초과 판매는 막는다.** `@Version` 덕분에 측정 10회(재시도 3회 설정 5회, 재시도 없음 설정 5회) 모두 `판매 수량 = 100 − 최종 재고 = 성공 주문 수`가 맞았고, 재고가 음수가 된 적도 없었다.
2. **품절까지 팔지는 못한다.** 수요(200건)가 재고(100개)보다 많은데도, 재시도 3회 설정에서 재고 48개가 남은 채 148건이 "시도 소진"으로 실패하고 환불됐다. 한 상품에 주문이 몰리면 낙관적 락 충돌이 너무 많아서, 3회 시도로는 부족하다. 그래서 이력서에 "재시도로 주문을 모두 처리"라고 쓰면 사실과 다르다. 대안으로는 조건부 원자 UPDATE(`quantity = quantity - ? WHERE quantity >= ?`), 비관적 락, 백오프를 넣은 재시도가 있다. 이 대안들은 구현하거나 측정하지 않았다.
3. **결과가 실행마다 똑같았다.** 성공 건수가 52건, 20건으로 매번 같았는데, Hikari 풀이 10개라서 실제로 동시에 도는 트랜잭션이 최대 10개로 제한된 영향으로 보인다(추정이고 검증하지 않음). 스레드 수나 풀 크기를 바꾸면 값도 달라질 수 있다.
4. **N+1의 원인은 JOIN FETCH가 없어서가 아니었다.** `Product.products`(상품 → CartItem 목록)가 `FetchType.EAGER`라서, 상품 1개를 읽을 때마다 `cart_items` 조회가 1번씩 더 나갔다. 상품 10개면 10번이다. 그래서 JOIN FETCH만 추가했을 때는 14회 그대로였다. LAZY로만 바꿔도 4회가 됐고, JOIN FETCH를 함께 써도 4회라 JOIN FETCH가 줄인 쿼리는 0회다(`raw/query_count_*_sql.txt`).
5. **LAZY 전환은 measure 브랜치에만 있다.** LAZY 전환과 JOIN FETCH 변경은 아직 `dev`/`main`에 반영되지 않았다. 이력서에 쓰려면 먼저 병합해야 한다. 그리고 동시성 측정은 EAGER 상태 코드로 한 것이라, LAZY로 바꾼 뒤에는 응답 시간이 달라질 수 있다.

## 측정 조건

### 환경 (`raw/concurrency_max_attempts_*_env.txt`에서 수집)

| 항목 | 값 |
|---|---|
| 머신 | Apple M4, 10코어, 16GB RAM, macOS 15.7.4 (aarch64) |
| JDK | Eclipse Adoptium Temurin 24.0.2+12 |
| 빌드 | Apache Maven 3.9.5 (`./mvnw`) |
| 프레임워크 | Spring Boot 4.0.1, Spring Framework 7.0.2, Hibernate ORM 7.2.0.Final, HikariCP 7.0.2 (풀 최대 10, 기본값) |
| DB | Testcontainers 2.0.3 `postgres:16` → PostgreSQL 16.15, 격리 수준 read committed |
| 컨테이너 런타임 | Docker 29.5.2 (Docker Desktop, 앱과 같은 머신) |
| 테스트 | JUnit Jupiter 6.0.1, Mockito 5.20.0 |

### 1) 동시 주문 테스트

- 코드: [`ConcurrentOrderMeasurement.java`](../../src/test/java/com/ecommerce/project/measure/ConcurrentOrderMeasurement.java)
- 측정 대상 코드: `dev`의 `1223b2b`(낙관적 락 + 재시도)에 시도 횟수 프로퍼티(`order.stock.max-attempts`)만 추가한 상태(`109ae64`). `Product.products`는 EAGER였다.
- 데이터는 회차마다 새로 만든다. 상품 1개(재고 100), 사용자 200명, 사용자마다 주소 1개와 장바구니(해당 상품 수량 1)를 둔다.
- 실행 방식: 스레드 50개 고정 풀에 200건을 넣고, `CountDownLatch` 1개로 동시에 출발시킨다. 각 작업은 `OrderService.placeOrder(..., pgName="Stripe", pgPaymentId="pi_measure_i")`를 호출한다.
- 반복 횟수는 설정별로 워밍업 1회 + 측정 5회다. 표의 값은 측정 5회의 평균이고, 응답 시간은 1,000건(5회 × 200건)의 평균이다.
- 초과 판매는 DB를 직접 조회해서 판정한다. `최종 재고 < 0`이거나 `100 − 최종 재고 ≠ order_items 수량 합`이거나 `order_items 수량 합 ≠ 성공 건수`이면 초과 판매로 본다.
- 실행 명령:

```bash
./mvnw -B test -Dmeasure=true -Dtest='ConcurrentOrder*MeasurementTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### 2) N+1 쿼리 수

- 코드: [`CartQueryCountMeasurementTest.java`](../../src/test/java/com/ecommerce/project/measure/CartQueryCountMeasurementTest.java)
- 데이터: 판매자 1명, 카테고리 1개, 상품 10개, 구매자 1명의 장바구니(상품 10개 × 수량 1). 카테고리와 판매자가 여러 개면 쿼리 수가 달라질 수 있다.
- 측정 방법: 읽기 전용 `TransactionTemplate` 안에서 서비스를 호출한다(웹 요청의 open-session-in-view를 흉내 낸 것). Hibernate Statistics의 `prepareStatementCount`와 `StatementInspector`로 잡은 SQL 개수를 함께 기록했는데, 두 값은 모두 일치했다.
- 반복 횟수는 시나리오별 3회이고, 매번 같은 값이 나왔다.
- 비교한 코드 버전은 다음과 같다. 테스트 코드와 데이터는 모두 같고, 메인 코드만 다르다.

| 라벨 | 메인 코드 | getCart | getAllProducts |
|---|---|---|---|
| `before` | `dbf30c8`에서 JOIN FETCH 변경을 되돌린 상태(= `dev` 코드) | 14 | 15 |
| `after_join_fetch` | `dbf30c8` (getCart가 JOIN FETCH 쿼리를 사용) | 14 | 15 |
| `lazy_only` | `8843e6c`에서 JOIN FETCH 변경을 되돌린 상태 (`Product.products` LAZY) | 4 | 4 |
| `after_join_fetch_lazy` | `8843e6c` (JOIN FETCH + LAZY) | 4 | 4 |

```bash
./mvnw -B test -Dmeasure=true -Dtest=CartQueryCountMeasurementTest -Dsurefire.failIfNoSpecifiedTests=false -Dmeasure.label=<label>
```

## 원본 파일

| 파일 | 내용 |
|---|---|
| `raw/concurrency_max_attempts_{3,1}_runs.csv` | 회차별 요약 (워밍업 포함, `warmup` 열로 구분) |
| `raw/concurrency_max_attempts_{3,1}_requests.csv` | 요청별 결과, 응답 시간, 오류 메시지 (1,200행) |
| `raw/concurrency_max_attempts_{3,1}_env.txt` | 실행 환경과 파라미터 |
| `raw/query_count_<label>.csv` | 시나리오·반복별 쿼리 수 |
| `raw/query_count_<label>_sql.txt` | 첫 반복에서 실행된 SQL 전체 |
