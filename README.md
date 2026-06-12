# E-Commerce Backend API

Spring Boot 기반 이커머스 백엔드 REST API. 상품·주문·결제·인증 등 온라인 쇼핑몰의 핵심 기능을 제공하며, 역할(Role) 기반 권한 관리와 Stripe 결제 연동을 포함합니다.

<!-- TODO: 배포 후 링크 채우기 -->
- 🔗 **API 문서 (Swagger)**: `https://<배포주소>/swagger-ui.html`
- 🖥️ **프론트엔드 레포**: [ecommerce-frontend](https://github.com/Dawit-lee/ecommerce-frontend)
- 🚀 **라이브 데모**: `https://<배포주소>` <!-- 배포 완료 후 업데이트 -->

---

## 🛠️ 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 24 |
| Framework | Spring Boot 4.0, Spring Web MVC |
| Security | Spring Security, JWT (jjwt) |
| Persistence | Spring Data JPA, Hibernate |
| Database | PostgreSQL (AWS RDS) |
| Payment | Stripe |
| Docs | SpringDoc OpenAPI (Swagger UI) |
| Mapping | ModelMapper |
| Build / Deploy | Maven, Docker |

---

## ✨ 주요 기능

### 인증 / 인가
- 회원가입 · 로그인 · 로그아웃 (JWT 쿠키 기반 인증)
- 역할 기반 접근 제어: `USER` / `SELLER` / `ADMIN`

### 상품 / 카테고리
- 상품 등록·수정·삭제 및 이미지 업로드 (관리자 / 판매자)
- 공개 상품 목록 조회, 카테고리별 조회, 키워드 검색
- 페이지네이션 · 정렬 지원

### 장바구니 / 주문
- 장바구니 생성, 상품 추가·수량 변경·삭제
- 주문 생성 및 주문 상태 관리 (관리자 / 판매자)

### 결제
- Stripe 연동 결제 (PaymentIntent / client secret 발급)

### 배송지
- 사용자 배송지 CRUD

### 분석
- 관리자용 주문/매출 분석 API (`/api/admin/app/analytics`)

---

## 🧱 아키텍처

```
Client (React/Vite)
      │  REST API (JSON)
      ▼
┌─────────────────────────────────────────────┐
│  Controller  →  Service  →  Repository (JPA) │
│       ▲             ▲                         │
│  Spring Security (JWT Filter)                 │
└─────────────────────────────────────────────┘
      │
      ▼
PostgreSQL (AWS RDS)        Stripe API
```

- **계층형 구조**: Controller / Service / Repository 분리
- **DTO 매핑**: ModelMapper로 Entity ↔ DTO 변환, API 응답에서 엔티티 직접 노출 방지
- **전역 예외 처리**: `@RestControllerAdvice` 기반 일관된 에러 응답
- **API 문서 자동화**: SpringDoc으로 Swagger UI 제공

---

## 📚 API 개요

베이스 경로: `/api`

| 영역 | 대표 엔드포인트 |
|------|----------------|
| Auth | `POST /api/auth/signin`, `POST /api/auth/signup`, `POST /api/auth/signout` |
| Product | `GET /api/public/products`, `POST /api/admin/categories/{categoryId}/product` |
| Category | `GET /api/public/categories`, `POST /api/admin/categories` |
| Cart | `GET /api/carts/users/cart`, `POST /api/carts/products/{productId}/quantity/{quantity}` |
| Order | `POST /api/order/users/payments/{paymentMethod}`, `GET /api/admin/orders` |
| Payment | `POST /api/order/stripe-client-secret` |
| Address | `GET /api/addresses`, `POST /api/addresses` |
| Analytics | `GET /api/admin/app/analytics` |

> 전체 명세는 실행 후 **Swagger UI**(`/swagger-ui.html`)에서 확인할 수 있습니다.

---

## 🚀 실행 방법

### 사전 요구사항
- Java 24
- PostgreSQL (로컬 또는 RDS)
- (선택) Docker

### 1. 환경변수 설정

시크릿은 코드에 포함되지 않으며 환경변수로 주입합니다.

| 변수 | 설명 | 필수 |
|------|------|------|
| `SPRING_DATASOURCE_URL` | JDBC URL (예: `jdbc:postgresql://localhost:5432/myapp`) | ✅ |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자명 (기본값 `postgres`) | ⬜ |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 | ✅ |
| `JWT_SECRET` | JWT 서명 시크릿 | ✅ |
| `STRIPE_SECRET_KEY` | Stripe 시크릿 키 | ✅ |

### 2. 로컬 실행

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/myapp"
export SPRING_DATASOURCE_PASSWORD="yourpassword"
export JWT_SECRET="your-jwt-secret"
export STRIPE_SECRET_KEY="sk_test_xxx"

./mvnw spring-boot:run
```

### 3. Docker 실행

```bash
docker build -t ecommerce-backend .
docker run -p 5000:5000 \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://<host>:5432/myapp" \
  -e SPRING_DATASOURCE_PASSWORD="yourpassword" \
  -e JWT_SECRET="your-jwt-secret" \
  -e STRIPE_SECRET_KEY="sk_test_xxx" \
  ecommerce-backend
```

기본 포트는 `5000`입니다.

---

## 📁 프로젝트 구조

```
src/main/java/com/ecommerce/project
├── config/         # 앱 설정 (Swagger, WebMvc, Bean)
├── controller/     # REST 컨트롤러
├── service/        # 비즈니스 로직
├── repositories/   # Spring Data JPA 리포지토리
├── model/          # JPA 엔티티
├── payload/        # 요청/응답 DTO
├── security/       # Spring Security + JWT
├── exceptions/     # 전역 예외 처리
└── util/           # 유틸리티
```

---

## 🗺️ 향후 개선 (Roadmap)

- [ ] Docker 기반 AWS EC2 배포
- [ ] GitHub Actions CI/CD 파이프라인
- [ ] 도메인 연결 및 HTTPS 적용
- [ ] 테스트 커버리지 확대
