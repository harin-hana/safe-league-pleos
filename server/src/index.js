// 세이프 리그 서버 (Cloudflare Workers + D1). repo_draft/server/API.md 그대로.
// 주행 중 통신 없음. 도착 때 POST /trip 한 번, 다음 출발 카드에서 GET /prize 한 번.
// 데모 리그 키(WORK|HOME|PM, HOME|WORK|AM)는 날짜와 무관하게 시드(trip_date 'demo', 전날 'demo-1')를 같은 날로 본다.

const DEMO_KEYS = new Set(['WORK|HOME|PM', 'HOME|WORK|AM']);
// 다음 날 카드(M19b 안 C): 지난주 순위, 근거 요일, 도장 수, 셋이 차면 고를 둘. 좌표는 예시(블루핸즈는 제휴 가정)
const PRIZE = { has: true, rank: 3, days: '월, 수, 금', stamps: 3, choices: [
  { name: '블루핸즈 안전 점검', km: 4.1, extra_min: 12, lat: 37.5048, lng: 127.0251 },
  { name: '길 위 카페 아메리카노 한 잔', km: 2.3, extra_min: 4, lat: 37.4059, lng: 127.0986 } ] };

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json; charset=utf-8' } });
const r1 = (v) => Math.round(v * 10) / 10;

async function board(db, key, date, meDriver) {
  const { results } = await db.prepare(
    `SELECT t.driver_id, d.nickname, t.score, t.clean_km, t.penalty_count FROM trip t JOIN driver d ON d.id = t.driver_id
     WHERE t.league_key = ? AND t.trip_date = ? AND t.eligible = 1
     ORDER BY t.score DESC, t.clean_km DESC, t.penalty_count ASC`).bind(key, date).all();
  const rows = results.map((r, i) => ({ rank: i + 1, name: r.driver_id === meDriver ? '나' : r.nickname, score: r.score, me: r.driver_id === meDriver }));
  const mine = rows.find(r => r.me);
  return { rows, my_rank: mine ? mine.rank : 0, total: rows.length, league: rows.length >= 3 };
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname.split('/').filter(Boolean);
    const db = env.DB;
    try {
      if (req.method === 'POST' && p[0] === 'trip') {
        const b = await req.json();
        const demo = DEMO_KEYS.has(b.league_key);
        const date = demo ? 'demo' : b.trip_date;
        await db.prepare(`INSERT OR IGNORE INTO driver(id, nickname, created_at) VALUES (?, ?, ?)`).bind(b.driver_id, b.nickname || '나', new Date().toISOString()).run();
        await db.prepare(`UPDATE driver SET nickname = ? WHERE id = ?`).bind(b.nickname || '나', b.driver_id).run();
        // 데모 키는 운전자마다 한 행만(같은 차가 여러 번 돌아도 순위표에 쌓이지 않게)
        if (demo) await db.prepare(`DELETE FROM trip WHERE driver_id = ? AND league_key = ? AND trip_date = ?`).bind(b.driver_id, b.league_key, date).run();
        await db.prepare(
          `INSERT OR IGNORE INTO trip(driver_id, league_key, trip_date, started_at, distance_km, duration_s, eta_s, eligible,
             d1, d1_jam, d2, d2_jam, d3, d4, d5, d6, d7, d8, b1, b2, b3, b4, b5, b6, penalty_sum, bonus_sum, score, clean_km, penalty_count, combo_max)
           VALUES (?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,?,?,?,?)`)
          .bind(b.driver_id, b.league_key, date, b.started_at, b.distance_km, b.duration_s, b.eta_s, b.eligible ? 1 : 0,
            b.d1 | 0, b.d1_jam | 0, b.d2 | 0, b.d2_jam | 0, b.d3 | 0, b.d4 | 0, b.d5 | 0, b.d6 | 0, b.d7 | 0, b.d8 | 0,
            b.b1 | 0, b.b2 | 0, b.b3 | 0, b.b4 | 0, b.b5 | 0, b.b6 | 0, b.penalty_sum, b.bonus_sum, b.score, b.clean_km, b.penalty_count | 0, b.combo_max | 0).run();
        // 데모 키에서는 시연 차량의 옛 기록(driver 'me')을 내 것으로 본다(아침 한 줄, 어제 고스트)
        const meHist = demo ? 'me' : b.driver_id;
        const out = await board(db, b.league_key, date, b.driver_id);
        const [start, end, slot] = b.league_key.split('|');
        const otherSlot = slot === 'PM' ? 'AM' : 'PM';
        const otherKey = demo ? (slot === 'PM' ? 'HOME|WORK|AM' : 'WORK|HOME|PM') : `${end}|${start}|${otherSlot}`;
        const morning = slot === 'PM' ? await db.prepare(`SELECT score FROM trip WHERE driver_id = ? AND league_key = ? AND trip_date = ? AND eligible = 1 ORDER BY started_at LIMIT 1`).bind(meHist, otherKey, date).first() : null;
        // 문구는 앱이 만든다(조사 처리). 아침 기록이 없으면 morning_rank 0
        let morning_rank = 0, morning_score = null;
        if (morning) { morning_rank = (await board(db, otherKey, date, meHist)).my_rank; morning_score = morning.score; }
        const yday = demo ? 'demo-1' : new Date(new Date(b.trip_date).getTime() - 86400000).toISOString().slice(0, 10);
        const ghost = await db.prepare(`SELECT score FROM trip WHERE driver_id = ? AND league_key = ? AND trip_date = ? ORDER BY started_at LIMIT 1`).bind(meHist, b.league_key, yday).first();
        return json({ ...out, morning_rank, morning_score, ghost_diff: ghost ? r1(b.score - ghost.score) : null });
      }
      if (req.method === 'GET' && p[0] === 'league' && p.length === 3) {
        const key = decodeURIComponent(p[1]);
        return json(await board(db, key, DEMO_KEYS.has(key) ? 'demo' : p[2], ''));
      }
      if (req.method === 'GET' && p[0] === 'weekly' && p.length === 3) {
        const key = decodeURIComponent(p[1]);
        const { results } = await db.prepare(
          `SELECT d.nickname, t.score FROM trip t JOIN driver d ON d.id = t.driver_id WHERE t.league_key = ? AND t.eligible = 1 AND (t.trip_date LIKE ? OR t.trip_date LIKE 'demo%')`)
          .bind(key, `${p[2]}%`).all();
        const by = {};
        for (const r of results) (by[r.nickname] ||= []).push(r.score);
        const top = Object.entries(by).map(([name, s]) => ({ name, sum: r1(s.sort((a, b) => b - a).slice(0, 3).reduce((a, b) => a + b, 0)) }))
          .sort((a, b) => b.sum - a.sum).slice(0, 3);
        return json({ week: p[2], top, prize: PRIZE });
      }
      if (req.method === 'GET' && p[0] === 'prize' && p.length === 2) return json(PRIZE);
      if (req.method === 'GET' && p.length === 0) return json({ ok: true, service: 'safe-league' });
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e) }, 500);
    }
  },
};
