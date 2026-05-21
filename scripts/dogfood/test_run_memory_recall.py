"""dogfood_test.py 内部 helper 的单元测试。

运行：cd server/scripts/dogfood && pytest test_run_memory_recall.py -v
"""

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import dogfood_test as dt  # noqa: E402


# ---------------- RECALL_DISTRACT_MESSAGES 保护测试 ----------------

def test_distract_pool_长度恰好35():
    """填充池数量必须固定 35，骨架据此推 plant 出短期窗口 32 条。"""
    assert len(dt.RECALL_DISTRACT_MESSAGES) == 35


def test_distract_pool_不含场景关键词避免污染():
    """
    填充消息绝不能巧合提到任何场景的 expected_keywords，
    否则 AI 在 distract 阶段回应时若顺嘴提到，会假阳性 PASS 召回测试。
    """
    forbidden = [
        # profile 场景关键词
        "绵阳", "四川", "川", "杭州", "王莎莎", "汤圆",
        # summary 场景关键词
        "寿司", "刺身", "三文鱼",
        # rag 场景关键词
        "成都", "周三", "星期三", "出差", "春熙路",
    ]
    for msg in dt.RECALL_DISTRACT_MESSAGES:
        for word in forbidden:
            assert word not in msg, f"填充消息 '{msg}' 含场景关键词 '{word}'"
