-- 세이프 리그 서버 스키마 (Cloudflare D1). 좌표와 궤적은 저장하지 않는다.
CREATE TABLE IF NOT EXISTS driver (
  id TEXT PRIMARY KEY,            -- VIN 해시(익명)
  nickname TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trip (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  driver_id TEXT NOT NULL REFERENCES driver(id),
  league_key TEXT NOT NULL,       -- 출발지격자|목적지격자|시간대(AM, PM)
  trip_date TEXT NOT NULL,        -- YYYY-MM-DD (기기 시계)
  started_at TEXT NOT NULL,
  distance_km REAL NOT NULL,
  duration_s INTEGER NOT NULL,
  eta_s INTEGER NOT NULL,         -- 출발 때 예상 소요
  eligible INTEGER NOT NULL,      -- 전제 6개 통과 여부(0, 1)
  d1 INTEGER DEFAULT 0, d1_jam INTEGER DEFAULT 0,   -- 급제동, 정체 중 급제동
  d2 INTEGER DEFAULT 0, d2_jam INTEGER DEFAULT 0,
  d3 INTEGER DEFAULT 0, d4 INTEGER DEFAULT 0, d5 INTEGER DEFAULT 0,
  d6 INTEGER DEFAULT 0, d7 INTEGER DEFAULT 0, d8 INTEGER DEFAULT 0,
  b1 INTEGER DEFAULT 0, b2 INTEGER DEFAULT 0, b3 INTEGER DEFAULT 0,
  b4 INTEGER DEFAULT 0, b5 INTEGER DEFAULT 0, b6 INTEGER DEFAULT 0,
  penalty_sum REAL NOT NULL,      -- 감점 합(보정 반영)
  bonus_sum REAL NOT NULL,        -- 가산 합(상한 10)
  score REAL NOT NULL,            -- 100 - penalty_sum*10/km + bonus, 0~110
  clean_km REAL NOT NULL,         -- 동점 1순위
  penalty_count INTEGER NOT NULL, -- 동점 2순위
  combo_max INTEGER DEFAULT 0,
  UNIQUE(driver_id, league_key, trip_date, started_at)   -- 멱등 처리
);
CREATE INDEX IF NOT EXISTS idx_trip_league_date ON trip(league_key, trip_date);

-- 일일 순위: 같은 리그, 같은 날, eligible=1, score desc, clean_km desc, penalty_count asc
-- 주간 왕중왕: driver별 월~금 상위 3회 score 합
