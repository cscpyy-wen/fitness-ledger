FROM python:3.12-slim@sha256:7a8b475003c4fe15a2cd4e55e5cfc2f3560bdc9333d624f24cdd6d4340fd7a17

WORKDIR /app
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FORWARDED_ALLOW_IPS=127.0.0.1,::1

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app \
    && install -d -o 10001 -g 10001 -m 0700 /var/lib/meal-analyzer

COPY --chown=10001:10001 requirements.lock ./
RUN python -m pip install --no-cache-dir --require-hashes -r requirements.lock
COPY --chown=10001:10001 meal_schema.py state_store.py meal_analyzer_server.py ./

USER 10001:10001

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import json,urllib.request; opener=urllib.request.build_opener(urllib.request.ProxyHandler({})); response=opener.open('http://127.0.0.1:8080/readyz', timeout=2); assert response.status == 200 and json.load(response) == {'status': 'ready'}"]
CMD ["uvicorn", "meal_analyzer_server:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers"]
