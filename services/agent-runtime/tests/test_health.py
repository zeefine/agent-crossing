from fastapi.testclient import TestClient

from agent_runtime.main import create_app


def test_health() -> None:
    client = TestClient(create_app())

    response = client.get("/api/health")

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["status"] == "UP"
    assert body["data"]["service"] == "agent-runtime"

