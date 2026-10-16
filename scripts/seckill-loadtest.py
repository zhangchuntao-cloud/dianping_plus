#!/usr/bin/env python3
"""
秒杀压测 + 一致性断言。

只依赖 Python 标准库，不需要装 JMeter / ab / wrk。

做三件事：
  1. 批量造登录态（直接按 Hash 结构写 Redis，跳过短信登录流程）
  2. 用线程池并发打秒杀接口，采集 QPS / P50 / P95 / P99 / 错误率
  3. 查 MySQL 断言「超卖数 = 0、重复单数 = 0」——这才是压测真正的结论

用法：
    python3 scripts/seckill-loadtest.py --users 300 --stock 100
    python3 scripts/seckill-loadtest.py --users 300 --stock 100 --chaos-drop-redis
"""

import argparse
import json
import random
import statistics
import string
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

# ---------------------------------------------------------------- 工具


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)


def mysql(sql, args):
    r = sh(
        f"mysql -h{args.mysql_host} -P{args.mysql_port} -u{args.mysql_user} "
        f"-p{args.mysql_password} -D {args.mysql_db} -N -B -e \"{sql}\""
    )
    if r.returncode != 0:
        raise RuntimeError(f"MySQL 查询失败: {r.stderr.strip()}")
    return r.stdout.strip()


def redis_cmd(cmd, args):
    r = sh(f"redis-cli -p {args.redis_port} {cmd}")
    if r.returncode != 0:
        raise RuntimeError(f"Redis 命令失败: {r.stderr.strip()}")
    return r.stdout.strip()


# ---------------------------------------------------------------- 造登录态


def seed_tokens(args):
    """
    按 RefreshTokenInterceptor 读取的结构造登录态：
    key = login:token:{token}，类型 Hash，字段 id / nickName / icon。
    """
    users = mysql(
        f"SELECT id, phone FROM tb_user ORDER BY id LIMIT {args.users}", args
    ).splitlines()
    if not users:
        raise RuntimeError("tb_user 没有数据")

    lines = []
    tokens = []
    for row in users:
        uid = row.split("\t")[0]
        token = uuid.uuid4().hex
        tokens.append((token, uid))
        lines.append(f"HSET login:token:{token} id {uid} nickName u{uid} icon i{uid}")
        lines.append(f"EXPIRE login:token:{token} 7200")

    # 走 --pipe 批量灌，比逐条 redis-cli 快两个数量级
    payload = "\n".join(lines) + "\n"
    r = subprocess.run(
        f"redis-cli -p {args.redis_port} --pipe",
        shell=True,
        input=payload,
        capture_output=True,
        text=True,
    )
    if "errors: 0" not in r.stdout:
        print(f"  ⚠ Redis 灌入可能有错: {r.stdout.strip()[-200:]}")

    print(f"  已造 {len(tokens)} 个登录态")
    return tokens


# ---------------------------------------------------------------- 压测


def fire(args, token):
    url = f"{args.base_url}/voucher-order/seckill/{args.voucher_id}"
    req = urllib.request.Request(url, method="POST", headers={"authorization": token})
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=args.timeout) as resp:
            body, status = resp.read().decode(), resp.status
    except urllib.error.HTTPError as e:
        body, status = e.read().decode(), e.code
    except Exception as e:  # 连接失败等
        body, status = json.dumps({"errorMsg": str(e)}), 0
    elapsed_ms = (time.perf_counter() - start) * 1000
    return elapsed_ms, status, body


def run_load(args, tokens):
    # 故意让所有请求尽可能同时发出，这才是"秒杀"而不是"浸泡测试"
    tokens = tokens[: args.users]
    print(f"  并发 {len(tokens)} 个请求打 POST /voucher-order/seckill/{args.voucher_id}")

    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=len(tokens)) as pool:
        results = list(pool.map(lambda t: fire(args, t[0]), tokens))
    wall = time.perf_counter() - start

    latencies = sorted(r[0] for r in results)
    ok = [r for r in results if r[1] == 200 and '"success":true' in r[2]]
    http_err = [r for r in results if r[1] != 200]
    biz_reject = [r for r in results if r[1] == 200 and '"success":true' not in r[2]]

    def pct(p):
        if not latencies:
            return 0.0
        idx = min(int(len(latencies) * p / 100), len(latencies) - 1)
        return latencies[idx]

    reject_reasons = {}
    for _, _, body in biz_reject:
        try:
            msg = json.loads(body).get("errorMsg", "unknown")
        except Exception:
            msg = "unparseable"
        reject_reasons[msg] = reject_reasons.get(msg, 0) + 1

    return {
        "wall_s": wall,
        "qps": len(results) / wall if wall > 0 else 0,
        "p50": pct(50),
        "p95": pct(95),
        "p99": pct(99),
        "max": latencies[-1] if latencies else 0,
        "success": len(ok),
        "http_err": len(http_err),
        "rejects": reject_reasons,
    }


# ---------------------------------------------------------------- 断言


def assert_consistency(args, label):
    voucher = args.voucher_id
    total = int(redis_cmd(f"GET 'seckill:total:{{{voucher}}}'", args) or 0)
    redis_stock = redis_cmd(f"GET 'seckill:stock:{{{voucher}}}'", args)
    redis_orders = redis_cmd(f"HLEN 'seckill:order:{{{voucher}}}'", args)

    order_cnt = int(mysql(f"SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={voucher}", args))
    dup_cnt = int(
        mysql(
            "SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order "
            f"WHERE voucher_id={voucher} GROUP BY user_id HAVING COUNT(*)>1) t",
            args,
        )
    )
    db_stock = int(mysql(f"SELECT stock FROM tb_seckill_voucher WHERE voucher_id={voucher}", args))

    print(f"\n  ── 一致性断言 [{label}] ──")
    print(f"    总库存(Redis)      : {total}")
    print(f"    已售订单(DB)       : {order_cnt}")
    print(f"    剩余库存(DB)       : {db_stock}")
    print(f"    剩余库存(Redis)    : {redis_stock}")
    print(f"    Redis 下单记录数   : {redis_orders}")

    failures = []
    # 1. 超卖：订单数不得超过总量
    if order_cnt > total:
        failures.append(f"超卖！订单数 {order_cnt} > 总库存 {total}")
    # 2. 重复单：同一用户同一券只能有一条
    if dup_cnt != 0:
        failures.append(f"重复下单！{dup_cnt} 个用户有重复订单")
    # 3. 库存不得为负
    if db_stock < 0:
        failures.append(f"DB 库存为负：{db_stock}")
    # 4. DB 库存与"总量-已售"必须吻合
    if db_stock != total - order_cnt:
        failures.append(f"库存漂移：DB={db_stock}，应为 总量{total}-已售{order_cnt}={total - order_cnt}")
    # 5. Redis 下单记录数应与已售一致（异步消费完成后）
    if int(redis_orders) != order_cnt:
        failures.append(f"Redis 记录数 {redis_orders} != DB 订单数 {order_cnt}")

    if failures:
        for f in failures:
            print(f"    ❌ {f}")
        return False
    print(f"    ✅ 超卖数=0  重复单数=0  库存一致")
    return True


def wait_drain(args, timeout=60):
    """
    等业务队列和重试队列**彻底**排空，确保异步消费已经完成。

    坑：RabbitMQ 管理 API 的 `messages` 只是 **ready** 消息数，
    已投递但尚未 ACK 的消息在 `messages_unacknowledged` 里。
    只看 `messages` 会在消费仍在进行时误判为"已排空"，
    导致后面的断言读到中间状态、得出假的不一致结论。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = sh(
            f"curl -s -u guest:guest http://{args.rabbitmq_host}:{args.rabbitmq_mgmt_port}/api/queues"
        ).stdout
        try:
            queues = {q["name"]: q for q in json.loads(out)}
        except Exception:
            time.sleep(1)
            continue
        pending = 0
        for name in ("seckill.order.queue", "seckill.retry.queue"):
            q = queues.get(name)
            if q:
                pending += q.get("messages", 0) + q.get("messages_unacknowledged", 0)
        if pending == 0:
            time.sleep(0.5)  # 让最后一批事务提交落定
            return True
        time.sleep(1)
    return False


# ---------------------------------------------------------------- main


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://127.0.0.1:8081")
    p.add_argument("--voucher-id", type=int, default=10)
    p.add_argument("--users", type=int, default=300, help="并发用户数")
    p.add_argument("--timeout", type=float, default=10.0)
    p.add_argument("--mysql-host", default="127.0.0.1")
    p.add_argument("--mysql-port", default="3307")
    p.add_argument("--mysql-user", default="root")
    p.add_argument("--mysql-password", default="root")
    p.add_argument("--mysql-db", default="dingping")
    p.add_argument("--redis-port", default="6380")
    p.add_argument("--rabbitmq-host", default="127.0.0.1")
    p.add_argument("--rabbitmq-mgmt-port", default="15673")
    p.add_argument(
        "--chaos-drop-redis",
        action="store_true",
        help="压测中途删掉 Redis 下单记录，模拟 Redis 丢 key（旧代码在这种场景下会重复下单）",
    )
    args = p.parse_args()

    print("=" * 62)
    print(f"秒杀压测  并发={args.users}  券={args.voucher_id}")
    print("=" * 62)

    print("\n[1/4] 造登录态")
    tokens = seed_tokens(args)

    if args.chaos_drop_redis:
        print("\n  ⚡ 混沌注入：删除 Redis 下单记录（模拟 Redis 丢 key / 内存淘汰）")
        redis_cmd(f"DEL 'seckill:order:{{{args.voucher_id}}}'", args)

    print("\n[2/4] 并发压测")
    stats = run_load(args, tokens)
    print(f"    QPS        : {stats['qps']:.1f}")
    print(f"    P50 / P95  : {stats['p50']:.1f} / {stats['p95']:.1f} ms")
    print(f"    P99 / Max  : {stats['p99']:.1f} / {stats['max']:.1f} ms")
    print(f"    成功       : {stats['success']}")
    print(f"    HTTP 错误  : {stats['http_err']}")
    if stats["rejects"]:
        print(f"    业务拒绝   : {stats['rejects']}")

    print("\n[3/4] 等待队列排空")
    drained = wait_drain(args)
    print(f"    {'已排空 ✅' if drained else '⚠ 超时未排空'}")

    print("\n[4/4] 一致性断言")
    ok = assert_consistency(args, "压测后")

    print("\n" + "=" * 62)
    print("结论：", "✅ 通过" if (ok and stats["http_err"] == 0) else "❌ 存在不一致")
    print("=" * 62)
    sys.exit(0 if (ok and stats["http_err"] == 0) else 1)


if __name__ == "__main__":
    main()
