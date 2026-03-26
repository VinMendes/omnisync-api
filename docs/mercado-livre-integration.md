# Integracao Mercado Livre: etapa 1

Esta primeira etapa cobre apenas a autenticacao OAuth para obter e armazenar `access_token` e `refresh_token`.

## 1. O que o Mercado Livre exige

Para acessar a API do vendedor, o fluxo e `authorization_code`.
Nao existe token util apenas com `client_id` e `client_secret`.

Voce precisa:

1. Criar a aplicacao no painel do Mercado Livre.
2. Cadastrar um `redirect_uri`.
3. Redirecionar o usuario para autorizar a conta.
4. Receber `code` no callback.
5. Trocar `code` por `access_token` e `refresh_token`.

## 2. Variaveis de ambiente

Defina:

```bash
export MELI_CLIENT_ID=seu-client-id
export MELI_CLIENT_SECRET=seu-client-secret
export MELI_REDIRECT_URI=http://localhost:8080/api/integrations/mercadolivre/callback
export MELI_STATE_SECRET=uma-chave-forte-para-o-state
```

O `redirect_uri` cadastrado na aplicacao do Mercado Livre precisa ser exatamente o mesmo valor usado pela API.

## 3. Estrutura implementada

- `MercadoLivreAuthController`
  Expondo `/api/integrations/mercadolivre/connect-url` e `/api/integrations/mercadolivre/callback`.
- `MercadoLivreAuthService`
  Gera a URL de autorizacao, valida o `state`, troca o `code` por token e salva a integracao.
- `MercadoLivreClient`
  Encapsula as chamadas HTTP de OAuth no Mercado Livre.
- `MarketplaceTokenService`
  Entrega um token valido para chamadas futuras e faz refresh quando necessario.
- `MarketplaceIntegration`
  Entidade persistida na tabela `marketplace_integrations`.

## 4. Fluxo com frontend

O fluxo principal deve ser este:

1. O usuario faz login no OmniSync.
2. O frontend chama `GET /api/integrations/mercadolivre/connect-url?systemClientId=...`.
3. A API devolve a `authorizationUrl`.
4. O frontend redireciona o navegador para o Mercado Livre.
5. O Mercado Livre redireciona de volta para uma rota do frontend com `code` e `state`.
6. O frontend envia `code` e `state` para `POST /api/integrations/mercadolivre/exchange`.
7. A API valida que o `state` pertence ao `system_client` do usuario autenticado.
8. A API troca o `code` por token e persiste em `marketplace_integrations`.

## 5. Passo a passo para testar

### 4.1 Criar um system client

Use o fluxo ja existente da API para criar um registro em `system_client`.
Voce vai precisar do `id`.

### 4.2 Gerar a URL de autorizacao

Com a API autenticada:

```http
GET /api/integrations/mercadolivre/connect-url?systemClientId=1
```

Resposta esperada:

```json
{
  "authorizationUrl": "https://auth.mercadolivre.com.br/authorization?..."
}
```

### 4.3 Autorizar no Mercado Livre

Abra a `authorizationUrl` no navegador e conclua a autorizacao da conta.

### 4.4 Receber o callback

O Mercado Livre redireciona para:

```text
https://seu-frontend/callback?code=...&state=...
```

O frontend deve chamar:

```http
POST /api/integrations/mercadolivre/exchange
Authorization: Bearer <token-da-sua-api>
Content-Type: application/json
```

```json
{
  "code": "TG-...",
  "state": "..."
}
```

Nesse ponto a API:

- valida o `state`
- valida o usuario autenticado
- troca o `code` por token
- criptografa `access_token` e `refresh_token`
- salva na tabela `marketplace_integrations`

O endpoint `GET /callback` pode continuar existindo para testes manuais, mas o fluxo oficial da aplicacao deve usar `/exchange`.

## 6. Proximo passo

Depois dessa etapa estar funcionando, a proxima fatia correta e criar um client autenticado para chamar:

- `/users/me`
- `/users/{seller_id}/items/search`
- endpoints de itens
- endpoints de pedidos
