"""JWT issue/verify and the bad-PIN lockout."""

from app import auth


def setup_function():
    auth.record_success()  # reset lockout state between tests


def test_jwt_roundtrip():
    assert auth._valid(auth.issue_token())


def test_jwt_rejects_tampered_and_garbage():
    token = auth.issue_token()
    tampered = token[:-2] + ("aa" if not token.endswith("aa") else "bb")
    assert not auth._valid(tampered)
    assert not auth._valid("garbage")
    assert not auth._valid(None)


def test_lockout_engages_at_threshold():
    for _ in range(auth.LOCK_THRESHOLD):
        auth.record_failure()
    assert auth.login_locked() > 0


def test_below_threshold_not_locked():
    for _ in range(auth.LOCK_THRESHOLD - 1):
        auth.record_failure()
    assert auth.login_locked() == 0


def test_success_clears_lockout():
    for _ in range(auth.LOCK_THRESHOLD + 2):
        auth.record_failure()
    assert auth.login_locked() > 0
    auth.record_success()
    assert auth.login_locked() == 0
