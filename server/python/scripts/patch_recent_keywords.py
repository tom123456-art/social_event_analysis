"""Selective, audited topic repair restricted to the latest 14 publication days."""
import csv
import json
import re
import shutil
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

import clean_csv_keywords as topics
import patch_csv_keywords as selective

BAD = selective.REPLACE | selective.REMOVE | set('''
一场 图景 多家 对方 建部 空姐 比尔 盖茨 国务卿 瓦德 意长 小哥 媒体人 拍摄者
视频拍摄者 事件目击者 乘务员 旅客 亲生 原副 党组 成员 群体 同志
感觉 优势 照片 答案 全力 规划 定位 总结 现在 记录 空间 典型 没想到
体系 领航 策略 这次 今天 传统 龙头 收官 事关 发布会 主题 方向
高标准 高度重视 大家耐心 下面 时期 大势所趋 温度 临时 良师益友 噩耗 黑衣
多彩 万后 余万 筹码 常态 受委屈 工具 万元 亿元 分钟 一年 上半年
最新消息 作出反应 本网将 一贯主张 动态显示 调查核实 电话 输球 赛后
汇总 复式 小炮 新浪 新浪彩票 竞彩情报 竞彩大势 号码全汇总 期历史
转折点 话题 地缘 原副 天才 下套 下结论 开门红 新人 有人 这么
被扣 独家 他们 开学 入学 管道 勇士 名记 考量 约字 过量 打水漂 打水漂勇士
产业链人士 女子错 高校信息 表格 水柱 续约勇士 学校开学
'''.split())

# These rules describe observable events, not inferred background about named people.
EVENTS = [
    (r'退票|错买机票|买错机票|买飞机票', ['机票退改签', '退票费用争议']),
    (r'自动续费|每周被扣|诱导弹窗|误触被扣', ['诱导扣费', '消费者权益保护']),
    (r'勇士.*续约|续约勇士|勇士.*降薪', ['篮球合同续约', '职业篮球']),
    (r'勇士|猛龙.*阵容', ['篮球阵容调整', '职业篮球']),
    (r'机器人.*(?:审厂|订单)', ['机器人供应链', '机器人产业']),
    (r'空姐.*(?:跪|踹|道歉)|(?:跪|踹).*空姐|空姐推.*磕碰', ['航空服务纠纷', '乘务劳动权益']),
    (r'比尔|盖茨', ['人工智能监管', '人工智能风险']),
    (r'弯弯的月亮|演唱会.*(?:加唱|缅怀)|(?:加唱|缅怀).*演唱会', ['演唱会曲目调整', '演出审批']),
    (r'月亮.*(?:泡面|创意)|(?:泡面|创意).*月亮', ['创意摄影', '影像创作']),
    (r'中秋|十五的月亮|月亮十七圆', ['中秋节', '赏月文化']),
    (r'现房销售|现房销售制', ['现房销售', '住房交付保障']),
    (r'住建部|住宅.*(?:标准|电梯)|层高', ['住宅建设标准', '住宅加装电梯']),
    (r'父母收入|高校信息采集|入学先填', ['高校信息采集', '个人信息保护']),
    (r'学校.*关停|学校.*分流', ['学校关停', '学生分流安置']),
    (r'供热管道|供暖.*爆裂', ['供热管网维护', '公共设施安全']),
    (r'婚育|结婚发钱', ['婚育补贴', '生育支持政策']),
    (r'银行.*定存', ['定期存款', '存款利率']),
    (r'丰收|秋收', ['秋收生产', '粮食保障']),
    (r'通电话|通话|国事访问|欢迎仪式|国宴', ['外交交流', '双边关系']),
    (r'快递员.*隐私|快递员.*洗澡|洗澡.*快递员', ['上门服务规范', '隐私保护']),
    (r'预付.*失联', ['预付款风险', '合同履约纠纷']),
    (r'项目.*(?:罗生门|讨债)|讨债.*获刑', ['工程款纠纷', '债务追偿']),
    (r'转账.*返还|赠.*分手|分手.*返还', ['婚恋财产纠纷', '赠与返还']),
    (r'退款|退款无果', ['消费退款', '售后服务纠纷']),
    (r'护栏.*(?:砸|车)|撞上护栏', ['道路交通事故', '事故责任认定']),
]
NORMALIZE = {'亚运': '亚运会', '黄金价格': '黄金价格', '亚运会': '亚运会'}

def date_of(row):
    value = row.get('publish_time', '').strip()
    for fmt in ('%Y/%m/%d %H:%M', '%Y/%m/%d %H:%M:%S', '%Y-%m-%d %H:%M:%S', '%Y-%m-%d %H:%M'):
        try:
            return datetime.strptime(value.replace('T', ' '), fmt).date()
        except ValueError:
            pass
    return None

def repair(row):
    old = selective.tokens(row['keywords'])
    canonical = []
    for pattern, terms in EVENTS:
        if re.search(pattern, row['title']):
            canonical = terms
            break
    # Existing meaningful words remain; the canonical topic replaces weak tokens only.
    context_bad = set()
    if canonical and re.search(r'弯弯的月亮', row['title']):
        context_bad.add('月亮')
    if canonical and re.search(r'通电话|欢迎仪式|国宴', row['title']):
        context_bad.update({'通电话', '欢迎仪式', '国宴'})
    if re.search(r'诱导弹窗|误触被扣', row['title']):
        context_bad.add('武器装备')
    bad = BAD | context_bad
    removed = [w for w in old if w in bad]
    kept = list(dict.fromkeys(NORMALIZE.get(w, w) for w in old if w not in bad))
    if not removed and kept == old:
        return row['keywords']
    if removed:
        candidates = canonical[:]
        if not candidates:
            evidence = {**row, 'keywords': '', 'content_text': ''}
            candidates, _ = topics.keywords(evidence)
        for word in candidates:
            if len(kept) >= max(2, len(old)):
                break
            if word not in kept and word not in bad:
                kept.append(word)
    return ','.join(kept)

def main():
    source = Path('data/crawler/social_event_real.csv')
    run = Path('runtime/keyword-recent14') / datetime.now().strftime('%Y%m%d-%H%M%S')
    run.mkdir(parents=True)
    backup = run / 'original.csv'
    shutil.copy2(source, backup)
    original_hash = topics.digest(backup)
    fields, rows = topics.read_csv(backup)
    latest = max(date_of(row) for row in rows if row['platform'].strip().upper() in topics.NEWS and date_of(row))
    start = latest - timedelta(days=13)
    output, changes, affected = run / 'patched.csv', [], 0
    with output.open('w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            date = date_of(row)
            new = row['keywords']
            if row['platform'].strip().upper() in topics.NEWS and date and start <= date <= latest:
                affected += 1
                new = repair(row)
            if new != row['keywords']:
                changes.append({'row': index + 2, 'date': str(date), 'title': row['title'], 'before': row['keywords'], 'after': new})
            writer.writerow({**row, 'keywords': new})
    after_fields, after = topics.read_csv(output)
    assert fields == after_fields and len(rows) == len(after)
    for old, new in zip(rows, after):
        assert all(old[k] == new[k] for k in fields if k != 'keywords')
        date = date_of(old)
        if old['keywords'] != new['keywords']:
            assert old['platform'].strip().upper() in topics.NEWS and start <= date <= latest
    with (run / 'changes.jsonl').open('w', encoding='utf-8') as handle:
        for change in changes:
            handle.write(json.dumps(change, ensure_ascii=False) + '\n')
    # Preview with the DB sample previously read, preserving SQL order and row limit.
    db_file = Path('runtime/momentum-db.json')
    if db_file.exists():
        db = json.loads(db_file.read_text(encoding='utf-8'))
        by_id = {(r['platform'].strip().upper(), r['content_id']): r for r in after}
        for row in db:
            match = by_id.get((row['platform'], row['content_id']))
            if match and date_of(match) and start <= date_of(match) <= latest:
                row['keywords'] = match['keywords']
        (run / 'preview-db.json').write_text(json.dumps(db, ensure_ascii=False), encoding='utf-8')
    assert topics.digest(source) == original_hash
    shutil.copyfile(output, source)
    assert topics.digest(source) == topics.digest(output)
    summary = {'start': str(start), 'end': str(latest), 'window_rows': affected, 'changed': len(changes), 'total_rows': len(rows), 'backup': str(backup), 'preview': str(run / 'preview-db.json')}
    (run / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
