"""Selective keyword repair: preserve all non-targeted keywords and columns."""
import argparse
import csv
import json
import re
import shutil
from collections import Counter
from datetime import datetime
from pathlib import Path

import clean_csv_keywords as topics

REPLACE = set('效率 方面 理性 这一 导弹 群众 效果 美军 经验总结 习近平'.split())
# Explicit exact matches only. Ambiguous brand names and scientific terms stay intact.
REMOVE = set('''
中国 美国 日本 韩国 朝鲜 英国 法国 德国 俄罗斯 乌克兰 伊朗 伊拉克 以色列 印度
加拿大 澳大利亚 新西兰 巴西 南非 意大利 西班牙 葡萄牙 荷兰 比利时 瑞士 瑞典
挪威 芬兰 丹麦 波兰 奥地利 希腊 土耳其 巴基斯坦 阿富汗 斯里兰卡 瑙鲁
越南 泰国 新加坡 马来西亚 印度尼西亚 菲律宾 埃及 沙特 阿联酋 卡塔尔
墨西哥 阿根廷 智利 秘鲁 哥伦比亚 古巴 尼泊尔 孟加拉国 缅甸 柬埔寨 老挝
北京 上海 天津 重庆 广东 广西 山东 山西 河南 河北 湖南 湖北 浙江 江苏 安徽
江西 福建 四川 贵州 云南 西藏 陕西 甘肃 青海 宁夏 新疆 内蒙古 辽宁 吉林
黑龙江 海南 台湾 香港 澳门 深圳 广州 珠海 东莞 佛山 成都 武汉 长沙 郑州
南京 杭州 苏州 无锡 合肥 南昌 济南 青岛 西安 昆明 贵阳 南宁 福州 厦门
沈阳 大连 长春 哈尔滨 兰州 西宁 银川 乌鲁木齐 拉萨 海口 三亚 名古屋
东京 大阪 首尔 伦敦 巴黎 柏林 华盛顿 纽约 旧金山 洛杉矶 莫斯科 迪拜
总统 副总统 主席 总理 副总理 首相 外长 防长 大使 发言人 新闻发言人
省长 副省长 市长 副市长 县长 书记 市委书记 省委书记 县委书记 村书记
秘书长 党组书记 省委常委 市委常委 部长 副部长 局长 副局长 主任 院长 校长
董事长 董事 总经理 副总经理 CEO 总裁 创始人 首席执行官 总监 负责人 官员 干部
记者 主持人 博主 网红 专家 学者 科学家 教授 院士 博士 硕士 研究生 教师 老师
学生 大学生 同学 家长 医生 医师 护士 民警 警察 交警 消防员 司机 驾驶员
游客 观众 球迷 粉丝 球员 主力球员 运动员 教练 主教练 裁判 队友 门将 主帅
首富 男子 女子 男生 女生 男孩 女孩 小男孩 小女孩 老人 大爷 大妈 小伙
父亲 母亲 爸爸 妈妈 父母 儿子 女儿 姐姐 弟弟 妻子 丈夫 夫妇 夫妻 家人
村民 居民 市民 网友 用户 客服 店主 商家 老板 员工 工人 农民 农民工 保姆
习近平 特朗普 拜登 普京 泽连斯基 马克龙 马克龙访日 高市早苗 内塔尼亚胡
哈梅内伊 拉夫罗夫 默茨 巴菲特 库克 哈登 凯恩 库里 穆里尼奥 穆罕默德
卡斯特罗 格雷厄姆 布什 诺兰 邓煜 亚马尔 勒布伦 王楚钦 孙颖莎 樊振东 马龙
张本智和 张本美和 郑钦文 吴艳妮 全红婵 陈芋汐 许昕 刘国梁 王皓 孙杨
梅西 C罗 姆巴佩 詹姆斯 东契奇 莫迪 万斯 王毅 黄仁勋 雷军 马斯克 刘强东
马云 张雪 于东来 蔡磊 吕良伟 鹿晗 王菲 刘翔 周杰伦 肖战 王一博 杨幂 赵丽颖
'''.split())

def tokens(value):
    return [w.strip() for w in re.split(r'[,，、/|；;]+', value) if w.strip()]

SPECIFIC = [
    (r'机车.*(?:夺冠|冠军|WSBK)|WSBK|机车.*(?:车队|赛车)|赛车', ['摩托车赛事', '赛车技术']),
    (r'机车.*(?:订单|门店|销售|二手|炒至)', ['摩托车市场', '车辆消费']),
    (r'机车.*(?:新手|驾照|投诉)', ['驾驶安全', '车辆管理']),
    (r'机车', ['摩托车产业', '摩托车技术']),
    (r'休假|休息权|带薪假', ['休假权益', '劳动保障']),
    (r'献血', ['无偿献血', '献血健康']),
    (r'猪肉.*收储|储备.*猪肉', ['猪肉收储', '储备调节']),
    (r'入欧|加入欧盟', ['区域一体化', '入盟进程']),
    (r'访日|访美|访俄|访韩', ['外交访问', '双边关系']),
    (r'天网2026|追逃|追赃', ['追逃追赃', '反腐合作']),
    (r'战损|炸毁|轰炸|军事部署|陆战队', ['军事行动', '军事安全']),
    (r'未来产业', ['未来产业', '产业创新']),
    (r'保健品|优思益', ['保健品监管', '产品调查']),
    (r'子女.*看望|内讧|赡养', ['家庭关系', '赡养责任']),
    (r'英雄故事|红色基因', ['英雄精神', '红色文化']),
    (r'向绿|绿色|低碳', ['绿色发展', '能源转型']),
]

def repair(row):
    old = tokens(row['keywords'])
    targeted = set(old) & (REPLACE | REMOVE)
    if not targeted:
        return row['keywords'], []
    kept = [w for w in old if w not in REPLACE | REMOVE]
    needed = sum(w in REPLACE for w in old)
    if needed:
        # Do not use the original keyword field or unrelated body templates as evidence.
        evidence = {**row, 'keywords': '', 'content_text': ''}
        candidates = [term for pattern, terms in SPECIFIC if re.search(pattern, row['title']) for term in terms]
        generated, _ = topics.keywords(evidence)
        candidates.extend(generated)
        for candidate in candidates:
            if needed == 0:
                break
            if candidate not in kept and candidate not in REPLACE | REMOVE:
                kept.append(candidate)
                needed -= 1
    return ','.join(kept), sorted(targeted)

def main():
    global REPLACE, REMOVE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--terms', nargs='+', help='Limit this run to these exact terms only')
    args = parser.parse_args()
    if args.terms:
        REPLACE, REMOVE = set(args.terms), set()
    source = Path('data/crawler/social_event_real.csv')
    run = Path('runtime/keyword-selective') / datetime.now().strftime('%Y%m%d-%H%M%S')
    run.mkdir(parents=True)
    backup = run / 'original.csv'
    shutil.copy2(source, backup)
    original_hash = topics.digest(backup)
    fields, rows = topics.read_csv(backup)
    output = run / 'patched.csv'
    changes, removed, frequency = [], Counter(), Counter()
    with output.open('w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            new, hits = repair(row)
            if new != row['keywords']:
                changes.append({'row': index + 2, 'title': row['title'], 'before': row['keywords'],
                                'after': new, 'targeted': hits})
                removed.update(w for w in tokens(row['keywords']) if w in REPLACE | REMOVE)
            frequency.update(tokens(new))
            writer.writerow({**row, 'keywords': new})
    after_fields, after_rows = topics.read_csv(output)
    assert fields == after_fields and len(rows) == len(after_rows)
    for before, after in zip(rows, after_rows):
        assert all(before[k] == after[k] for k in fields if k != 'keywords')
        assert not (set(tokens(after['keywords'])) & (REPLACE | REMOVE))
        for word in tokens(before['keywords']):
            if word not in REPLACE | REMOVE:
                assert word in tokens(after['keywords'])
        if not set(tokens(before['keywords'])) & (REPLACE | REMOVE):
            assert before['keywords'] == after['keywords']
    summary = {'rows': len(rows), 'changed': len(changes), 'unchanged': len(rows)-len(changes),
               'removed_terms': removed.most_common(), 'top30': frequency.most_common(30),
               'backup': str(backup), 'applied': args.apply}
    with (run / 'changes.jsonl').open('w', encoding='utf-8') as log:
        for change in changes:
            log.write(json.dumps(change, ensure_ascii=False) + '\n')
    if args.apply:
        assert topics.digest(source) == original_hash, 'Source changed during review'
        shutil.copyfile(output, source)
        assert topics.digest(source) == topics.digest(output)
    (run / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
