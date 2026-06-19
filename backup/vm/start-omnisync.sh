#!/bin/bash

echo "🚀 Iniciando deploy do OmniSync..."

PORT=8080
PROJECT_DIR=/home/ubuntu/omnisync-api
LOG_FILE=$PROJECT_DIR/omnisync.log

echo "🔎 Verificando processo na porta $PORT..."
PID=$(lsof -ti:$PORT)

if [ -n "$PID" ]; then
  echo "🛑 Matando processo $PID..."
  kill -9 $PID
else
  echo "✅ Nenhum processo rodando na porta $PORT"
fi

echo "📂 Entrando na pasta do projeto..."
cd "$PROJECT_DIR" || {
  echo "❌ Pasta do projeto não encontrada"
  exit 1
}

echo "📥 Atualizando código do Git..."
git pull || {
  echo "❌ Falha no git pull"
  exit 1
}

echo "⚙️ Gerando novo build..."
./gradlew bootJar || {
  echo "❌ Falha no build"
  exit 1
}

JAR_FILE=$(ls build/libs/*.jar | head -n 1)

if [ -z "$JAR_FILE" ]; then
  echo "❌ Nenhum jar encontrado"
  exit 1
fi

echo "🔥 Iniciando aplicação..."

nohup env \
DB_DRIVER="org.postgresql.Driver" \
DB_URL="jdbc:postgresql://146.235.47.211:5432/omnisync" \
DB_USERNAME="omnisync_user" \
DB_PASSWORD='V9#kT2!mQ7@zL4$rXp8&' \
MELI_CLIENT_ID="897819191598162" \
MELI_CLIENT_SECRET="zBbphu12CNLSdfBLlcLU9TMJqDPau3em" \
MELI_REDIRECT_URI="https://omnisync-web.vercel.app/" \
MELI_STATE_SECRET='ux+1s5O2upJMWLJhOI4wzTE82GjfhRX0vgF6hnsGJYY=' \
MAIL_USERNAME="omnisync69@gmail.com" \
MAIL_PASSWORD='bctbtdowsspyhrkg' \
java -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &

echo "⏳ Aguardando aplicação subir..."
sleep 10

NEW_PID=$(lsof -ti:$PORT)

if [ -n "$NEW_PID" ]; then
  echo "✅ OmniSync rodando com PID $NEW_PID"
  echo "📜 Logs:"
  echo "tail -f $LOG_FILE"
else
  echo "⚠️ Verifique o log:"
  echo "tail -f $LOG_FILE"
fi
