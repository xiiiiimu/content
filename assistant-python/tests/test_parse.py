import pytest
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_search_portrait_commercial():
    r = client.post(
        "/v1/parse",
        json={
            "taskId": 1,
            "postId": 2,
            "actorUserId": 3,
            "body": "@助手 找竖屏、允许商用的雨夜街景",
            "actorIsAuthor": False,
        },
    )
    assert r.status_code == 200
    body = r.json()
    assert body["intent"] == "SEARCH"
    assert body["search"]["aspectRatio"] == "PORTRAIT"
    assert body["search"]["licenseCommercial"] is True
    assert "雨夜" in (body["search"]["query"] or "")


def test_subscribe_needs_parent():
    r = client.post(
        "/v1/parse",
        json={"taskId": 1, "postId": 2, "actorUserId": 3, "body": "@助手 蹲这条的修改结果", "actorIsAuthor": False},
    )
    assert r.json()["clarify"] is not None


def test_subscribe_with_parent():
    r = client.post(
        "/v1/parse",
        json={
            "taskId": 1,
            "postId": 2,
            "actorUserId": 3,
            "parentCommentId": 99,
            "body": "@助手 蹲这条的修改结果",
            "actorIsAuthor": False,
        },
    )
    assert r.json()["subscribe"]["feedbackCommentId"] == 99


def test_purchase_enters_rule_tree():
    # PURCHASE has been an enabled tool since c79bf3b (order source wired); it is an ordinary condition, not unsupported.
    r = client.post(
        "/v1/parse",
        json={
            "taskId": 1,
            "postId": 2,
            "actorUserId": 3,
            "body": "@助手 从买过的人里抽1人 截止epoch:9999999999999",
            "actorIsAuthor": True,
        },
    )
    body = r.json()
    assert body["unsupportedTools"] == []
    assert body["clarify"] is None
    assert '"type":"PURCHASE"' in body["giveaway"]["ruleTreeJson"]


def test_like_only_giveaway():
    r = client.post(
        "/v1/parse",
        json={
            "taskId": 1,
            "postId": 2,
            "actorUserId": 3,
            "body": "@助手 从点赞过的人里抽1人 截止epoch:9999999999999",
            "actorIsAuthor": True,
        },
    )
    body = r.json()
    assert body["intent"] == "GIVEAWAY"
    assert '"type":"LIKE"' in body["giveaway"]["ruleTreeJson"]
    assert "COMMENT" not in body["giveaway"]["ruleTreeJson"]


def _parse(body, author=True, parent=None, context=None):
    return client.post(
        "/v1/parse",
        json={"taskId": 1, "postId": 2, "actorUserId": 3, "parentCommentId": parent, "body": body,
              "actorIsAuthor": author, "context": context or {}},
    ).json()


# A11: every condition the platform cannot evaluate must be reported, never silently dropped (r1 smoke a188-a211).
@pytest.mark.parametrize("text,tool", [
    ("@助手 点赞且转发的抽1人 截止epoch:9999999999999", "转发"),               # a188
    ("@助手 点赞并且分享到朋友圈的抽1人 截止epoch:9999999999999", "分享"),       # a189
    ("@助手 评论字数超过20字的抽1人 截止epoch:9999999999999", "评论字数"),       # a191
    ("@助手 关注满一年的老粉抽1人 截止epoch:9999999999999", "关注时长"),         # a192
    ("@助手 评论并@三个好友的抽2人 截止epoch:9999999999999", "@好友"),          # a194
    ("@助手 只有没关注我的人能参与，但必须关注，抽1人 截止epoch:9999999999999", "未关注（否定条件）"),  # a202
    ("@助手 关注的抽2人，送永久商用+转售授权 截止epoch:9999999999999", "转售授权"),  # a208
    ("@助手 点赞的抽1人，奖品是我另一个帖子的素材 截止epoch:9999999999999", "其他帖子的奖品"),  # a209
    ("@助手 点赞的抽1人，作者本人也参与 截止epoch:9999999999999", "作者本人参与"),  # a210
    ("@助手 评论的抽1人，同一个人可以多次评论增加中奖率 截止epoch:9999999999999", "同一人多次参与"),  # a211
])
def test_unsupported_condition_is_reported(text, tool):
    body = _parse(text)
    assert tool in body["unsupportedTools"]
    assert body["giveaway"] is None


def test_unsupported_reported_even_without_deadline():
    body = _parse("@助手 今晚8点 点赞且转发的抽1人")
    assert "转发" in body["unsupportedTools"]


def test_at_least_k_larger_than_conditions_clarifies():  # a199
    body = _parse("@助手 至少满足3项：点赞、评论，抽1人 截止epoch:9999999999999")
    assert body["giveaway"] is None
    assert body["clarify"] is not None


def test_at_least_with_filler_word_is_understood():
    body = _parse("@助手 至少满足2项：点赞、评论、收藏，抽1人 截止epoch:9999999999999")
    assert '"op":"AT_LEAST","k":2' in body["giveaway"]["ruleTreeJson"]


FEEDBACK = {"parentKind": "feedback", "parentIsFeedback": True}


@pytest.mark.parametrize("text", ["@助手 取消蹲这条", "@助手 不用蹲了", "@助手 我不想蹲这条，别通知我"])  # a116 a117 a120
def test_cancel_is_never_a_subscription(text):
    body = _parse(text, author=False, parent=99, context=FEEDBACK)
    assert body["subscribe"]["action"] == "CANCEL"


@pytest.mark.parametrize("text,target", [
    ("@助手 帮 @小王 也蹲一下这条", "OTHERS"),          # a115
    ("@助手 把这条的所有订阅者都通知一遍", "ALL"),       # a118
    ("@助手 给所有点赞的人都订阅这条", "ALL"),           # a119
])
def test_subscribe_for_others_or_all_is_marked(text, target):
    body = _parse(text, author=False, parent=99, context=FEEDBACK)
    assert body["subscribe"]["target"] == target


def test_plain_subscribe_is_self():
    body = _parse("@助手 蹲这条的修改结果", author=False, parent=99, context=FEEDBACK)
    assert body["subscribe"] == {"feedbackCommentId": 99, "action": "SUBSCRIBE", "target": "SELF"}


@pytest.mark.parametrize("kind", ["ordinary", "assistant"])  # a110-a113
def test_subscribe_parent_not_feedback_clarifies(kind):
    body = _parse("@助手 蹲这条的修改结果", author=False, parent=99,
                  context={"parentKind": kind, "parentIsFeedback": False})
    assert body["subscribe"] is None
    assert body["clarify"] is not None
