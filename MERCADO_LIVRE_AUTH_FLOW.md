# Fluxo Técnico de Autenticação Mercado Livre

Este documento descreve o fluxo OAuth do Mercado Livre adotado no OmniSync para conectar a conta do usuário da plataforma e persistir os tokens na tabela `marketplace_integrations`.

## Objetivo

Permitir que um usuário autenticado no OmniSync conecte sua conta do Mercado Livre, para que a API salve e gerencie:

- `access_token`
- `refresh_token`
- `expires_at`
- metadados da integração

Esses dados ficam vinculados ao `system_client` do usuário autenticado.

## Visão geral

O fluxo utilizado é `Authorization Code Grant`.

O Mercado Livre não devolve um token pronto para ser usado diretamente pelo frontend. O retorno correto do provedor é:

- `code`
- `state`

Depois disso, o frontend envia esses dados para a API, e a API troca o `code` por tokens em `POST https://api.mercadolibre.com/oauth/token`.

## Componentes envolvidos

### Frontend

Responsável por:

- solicitar a URL de autorização para a API
- redirecionar o navegador para o Mercado Livre
- capturar `code` e `state` no retorno do OAuth
- enviar `code` e `state` para a API

### API OmniSync

Responsável por:

- gerar a URL de autorização
- gerar e assinar o `state`
- validar o usuário autenticado
- validar o `state`
- trocar o `code` por `access_token` e `refresh_token`
- criptografar os tokens
- persistir a integração no banco

### Banco de dados

Tabela envolvida:

- `marketplace_integrations`

Schema atual:

```sql
CREATE TABLE marketplace_integrations (
    id BIGSERIAL PRIMARY KEY,
    system_client_id BIGINT NOT NULL,
    marketplace VARCHAR(30) NOT NULL,
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    expires_at TIMESTAMP NOT NULL,
    resource JSONB,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_marketplace_integrations_client
        FOREIGN KEY (system_client_id)
            REFERENCES system_client(id)
            ON DELETE CASCADE
);
```

Restrição:

```sql
UNIQUE (system_client_id, marketplace)
```

Isso significa que cada `system_client` pode ter no máximo uma integração ativa por marketplace.

## Configuração necessária

Variáveis de ambiente:

```bash
export MELI_CLIENT_ID=...
export MELI_CLIENT_SECRET=...
export MELI_REDIRECT_URI=https://omnisync-web.vercel.app/
export MELI_STATE_SECRET=...
```

Observações:

- `MELI_CLIENT_SECRET` nunca deve ir para o frontend.
- `MELI_STATE_SECRET` é um segredo interno da API usado para assinar o `state`.
- `MELI_REDIRECT_URI` deve ser exatamente igual ao valor cadastrado no app do Mercado Livre.

## Fluxo completo

### 1. Usuário faz login no OmniSync

O usuário primeiro precisa estar autenticado na própria plataforma.

A autenticação interna do OmniSync é separada da autenticação OAuth do Mercado Livre.

O token JWT do OmniSync identifica o usuário e, por consequência, o `system_client` ao qual ele pertence.

### 2. Frontend solicita a URL de autorização

O frontend chama:

```http
GET /api/integrations/mercadolivre/connect-url?systemClientId=<id>
Authorization: Bearer <jwt-do-omnisync>
```

Hoje a API gera a URL com:

- `response_type=code`
- `client_id`
- `redirect_uri`
- `state`

Exemplo de resposta:

```json
{
  "authorizationUrl": "https://auth.mercadolivre.com.br/authorization?response_type=code&client_id=...&redirect_uri=...&state=..."
}
```

### 3. API gera o `state`

O `state` é montado para proteger o fluxo OAuth contra adulteração e replay simples.

Estrutura lógica do payload:

```text
systemClientId:expiresAtEpochSeconds:uuid
```

Esse payload é assinado com `HmacSHA256` usando `MELI_STATE_SECRET`.

Formato final antes do Base64 URL-safe:

```text
systemClientId:expiresAtEpochSeconds:uuid:signature
```

Depois disso, a API aplica Base64 URL-safe sem padding.

Implementação atual:

- `MercadoLivreAuthService.generateAuthorizationUrl`
- `MercadoLivreAuthService.generateState`
- `MercadoLivreAuthService.sign`

### 4. Frontend redireciona o navegador

O frontend abre a `authorizationUrl`.

Exemplo:

```text
https://auth.mercadolivre.com.br/authorization?response_type=code&client_id=...&redirect_uri=https://omnisync-web.vercel.app/&state=...
```

### 5. Mercado Livre autentica o usuário e redireciona de volta

Após login/autorização, o Mercado Livre redireciona o navegador para o `redirect_uri` informado.

Exemplo real:

```text
https://omnisync-web.vercel.app/?code=TG-...&state=...
```

Se o navegador voltou com `code` e `state`, o OAuth do Mercado Livre funcionou corretamente.

### 6. Frontend captura `code` e `state`

O frontend deve ler esses parâmetros da URL imediatamente no carregamento da página de callback.

Exemplo de dados capturados:

```json
{
  "code": "TG-...",
  "state": "..."
}
```

### 7. Frontend envia `code` e `state` para a API

O frontend chama:

```http
POST /api/integrations/mercadolivre/exchange
Authorization: Bearer <jwt-do-omnisync>
Content-Type: application/json
```

Body:

```json
{
  "code": "TG-...",
  "state": "..."
}
```

Esse é o endpoint principal do fluxo.

## Validações executadas pela API

Ao receber `/exchange`, a API executa as seguintes etapas:

### 1. Resolve o usuário autenticado

A API usa o JWT interno do OmniSync para obter o email do usuário autenticado.

Depois disso, busca a entidade `User` ativa no banco.

### 2. Extrai o `system_client_id` do usuário

O `system_client_id` vem da tabela `users`.

Esse vínculo é importante porque os tokens do Mercado Livre não pertencem ao usuário individualmente no banco atual, e sim ao tenant `system_client`.

### 3. Valida o `state`

A API:

- decodifica o Base64 URL-safe
- separa os componentes
- recalcula a assinatura HMAC
- compara a assinatura recebida com a esperada
- valida a expiração do `state`
- extrai o `systemClientId` embutido

### 4. Garante que o `state` pertence ao tenant do usuário logado

A API compara:

- `systemClientId` do `state`
- `systemClientId` do usuário autenticado

Se forem diferentes, a integração é rejeitada.

Essa etapa impede que um usuário autenticado conecte uma conta do Mercado Livre em outro tenant apenas reaproveitando um `state` alheio.

### 5. Troca o `code` por tokens no Mercado Livre

A API chama:

```http
POST https://api.mercadolibre.com/oauth/token
Content-Type: application/x-www-form-urlencoded
```

Payload:

```text
grant_type=authorization_code
client_id=<MELI_CLIENT_ID>
client_secret=<MELI_CLIENT_SECRET>
code=<code>
redirect_uri=<MELI_REDIRECT_URI>
```

Resposta esperada do Mercado Livre:

```json
{
  "access_token": "...",
  "token_type": "bearer",
  "expires_in": 21600,
  "scope": "...",
  "user_id": 123456,
  "refresh_token": "..."
}
```

### 6. Persiste em `marketplace_integrations`

A API faz upsert lógico por:

- `system_client_id`
- `marketplace = MERCADO_LIVRE`

Campos persistidos:

- `access_token` criptografado
- `refresh_token` criptografado
- `expires_at = now + expires_in`
- `resource`
- `active = true`

Atualmente `resource` armazena:

- `scope`
- `token_type`
- `user_id`

## Refresh de token

Quando a API precisar usar o token para chamadas futuras ao Mercado Livre, ela utiliza `MarketplaceTokenService`.

Fluxo:

1. Busca a integração ativa em `marketplace_integrations`
2. Verifica se o token está expirado ou perto de expirar
3. Se necessário, usa o `refresh_token`
4. Atualiza `access_token`, `refresh_token` e `expires_at`

Chamada de refresh:

```text
grant_type=refresh_token
client_id=<MELI_CLIENT_ID>
client_secret=<MELI_CLIENT_SECRET>
refresh_token=<refresh_token>
```

## Endpoints da API

### Gerar URL de autorização

```http
GET /api/integrations/mercadolivre/connect-url?systemClientId=<id>
```

Retorno:

```json
{
  "authorizationUrl": "https://auth.mercadolivre.com.br/authorization?..."
}
```

### Trocar `code` por token e persistir integração

```http
POST /api/integrations/mercadolivre/exchange
```

Body:

```json
{
  "code": "TG-...",
  "state": "..."
}
```

Retorno esperado:

```json
{
  "message": "Mercado Livre account connected successfully.",
  "systemClientId": 1,
  "marketplace": "MERCADO_LIVRE",
  "active": true,
  "expiresAt": "2026-03-26T12:34:56",
  "resource": {
    "scope": "...",
    "token_type": "bearer",
    "user_id": 123456789
  }
}
```

## Sequência resumida

```text
Usuario autenticado no OmniSync
    -> Front chama /connect-url
    -> API gera authorizationUrl com state assinado
    -> Front redireciona para Mercado Livre
    -> Mercado Livre autentica e retorna code/state no redirect_uri
    -> Front captura code/state
    -> Front chama /exchange
    -> API valida usuario autenticado
    -> API valida state
    -> API troca code por token
    -> API criptografa os tokens
    -> API salva em marketplace_integrations
```

## Restrições e cuidados

- O `code` expira rapidamente e normalmente só pode ser usado uma vez.
- O frontend não deve persistir `access_token` do Mercado Livre.
- O frontend não deve conhecer `client_secret`.
- O `redirect_uri` precisa bater exatamente com o cadastrado no Mercado Livre.
- O `state` precisa ser validado no backend.
- O token do Mercado Livre não substitui o JWT interno do OmniSync.

## Status atual da implementação

O fluxo atual já suporta:

- geração de URL de autorização
- callback no frontend com `code/state`
- troca de `code` por token via backend
- persistência em `marketplace_integrations`
- refresh de token

Melhorias futuras possíveis:

- usar rota dedicada de callback no frontend em vez de `/`
- consultar `/users/me` após conectar para enriquecer `resource`
- registrar auditoria de conexão/desconexão
- criar endpoint para consultar status da integração
