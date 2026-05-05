FROM docker:27-cli

WORKDIR /app

RUN apk add --no-cache python3 py3-pip \
    && pip3 install --no-cache-dir --break-system-packages "pymongo>=4.7,<5"

COPY server.py /app/server.py
COPY API.py /app/API.py
COPY autoscale.py /app/autoscale.py
COPY config.py /app/config.py
COPY docker.py /app/docker.py
COPY mongo_app_user.py /app/mongo_app_user.py

ENV PYTHONUNBUFFERED=1

EXPOSE 8080
ENTRYPOINT ["python3", "/app/server.py"]
