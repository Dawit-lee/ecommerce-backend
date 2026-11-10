# HTTP 부하 테스트 (k6)

결과와 해석은 [`docs/measurements/LOADTEST.md`](../docs/measurements/LOADTEST.md)에 있습니다.

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | PostgreSQL 16(2 CPU, 2GB, `pg_stat_statements`), 앱(2 CPU, 1GB), Prometheus |
| `seed.sql` | 카테고리 50, 판매자 1,000, 구매자 10,000, 상품 1,000,000, 장바구니 5,000 (고정 시드) |
| `reset.sh` | 볼륨 삭제 → 앱 기동(스키마 생성) → 시드 |
| `read.js` | 상품 조회 혼합 시나리오 (목록 40%, 카테고리 20%, 키워드 20%, 가격 정렬 20%) |
| `run.sh` | VU 단계별로 k6 실행 + `docker stats`·`pg_stat_statements`·Prometheus(Hikari, CPU, GC) 수집 → `docs/measurements/loadtest-raw/<label>/` |
| `psql.sh` | DB 접속 |

```bash
cd loadtest
./reset.sh                          # 처음 한 번 (약 1분)
./run.sh baseline 60s 1 5 10 25 50  # 결과: docs/measurements/loadtest-raw/baseline/
```

Hikari 풀 크기는 `HIKARI_MAX=20 docker compose up -d app`으로 바꿀 수 있습니다(기본값 10).
