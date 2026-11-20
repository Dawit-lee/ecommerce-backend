# AGENTS.md

S-Com 이커머스 백엔드. Spring Boot(Java 24, Maven) + PostgreSQL 16 + JWT + Stripe.

## 명령
- 필수 환경변수: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `STRIPE_SECRET_KEY`(테스트 키 `sk_test_...`)
- 로컬 DB: `docker run -d --name ecom-pg -e POSTGRES_DB=myapp -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16`
- 빌드 + 테스트(CI와 같음): `./mvnw -B clean package` (DB가 떠 있어야 함)
- 실행: `./mvnw spring-boot:run` (포트 5000)
- 측정 테스트(평소엔 건너뜀): `./mvnw test -Dmeasure=true -Dtest=CartQueryCountMeasurementTest`
- HTTP 부하 테스트(k6): `loadtest/README.md` 참고

## 구조
- `src/main/java/com/ecommerce/project/` `controller` → `service` → `repositories`, 엔티티는 `model`, DTO는 `payload`
- `security/` Spring Security + JWT(쿠키), `exceptions/` 전역 예외 처리
- `src/main/resources/schema.sql` Hibernate가 만든 테이블 위에 추가 인덱스(매 기동 시 실행)
- `src/test/java/.../measure/` 동시성·쿼리 수 측정 테스트, 결과는 `docs/measurements/`
- `.github/workflows/ci-cd.yml` push·PR마다 빌드·테스트, `main` push는 Elastic Beanstalk 배포

## 규칙
- 컨트롤러는 엔티티 대신 `payload`의 DTO를 주고받는다.
- 인덱스는 `schema.sql`에 `CREATE INDEX IF NOT EXISTS`로 더한다(여러 번 실행돼도 안전하게).
- 재고 동시성 기본값은 `Product`의 `@Version`(낙관적 락) + 재시도(`order.stock.max-attempts`, 기본 3)다. `order.stock.lock=pessimistic`은 비교 측정용이다.
- 주문 저장이 실패하면 이미 결제된 Stripe 금액을 환불하는 보상 처리(`OrderServiceImpl.compensatePayment`)를 지우거나 우회하지 않는다.
- 락·재시도 방식을 바꾸면 측정 테스트로 다시 재서 결과를 `docs/measurements/`에 남긴다.

## 하지 말 것
- JWT 시크릿, Stripe 키, DB 비밀번호를 `application.properties`나 코드에 넣지 않는다(환경변수만).
- Stripe 라이브 키(`sk_live_...`)로 로컬 실행·테스트하지 않는다.
- 운영 DB에 직접 SQL을 실행하지 않는다. `main`에 머지하면 바로 배포되니 PR에서 CI 통과를 먼저 확인한다.
