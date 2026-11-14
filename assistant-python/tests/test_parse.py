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


def test_purchase_not_silently_ignored():
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
    assert "PURCHASE" in body["unsupportedTools"]
    assert body["clarify"] is not None


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
