FROM docker:27-cli

WORKDIR /app

RUN apk add --no-cache python3 py3-pip \
    && pip3 install --no-cache-dir --break-system-packages "pymongo>=4.7,<5"

COPY server.py /app/server.py

ENV PYTHONUNBUFFERED=1

EXPOSE 8080
ENTRYPOINT ["python3", "/app/server.py"]
