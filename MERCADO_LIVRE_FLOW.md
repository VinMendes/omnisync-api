# Mercado Livre: Endpoints e Fluxos Técnicos

## Escopo

Este documento descreve a implementação atual de integração com Mercado Livre no backend do OmniSync:

- endpoints HTTP expostos pela API
- endpoints externos consumidos no Mercado Livre
- fluxo de autenticação OAuth
- fluxo de criação de anúncio
- fluxo de edição de anúncio
- fluxo de remoção de anúncio
- fluxo de recebimento de notificação de vendas

O conteúdo abaixo reflete o código atual do projeto.

## Configuração

Variáveis e propriedades relevantes:

```properties
mercadolivre.client-id=${MELI_CLIENT_ID:}
mercadolivre.client-secret=${MELI_CLIENT_SECRET:}
mercadolivre.redirect-uri=${MELI_REDIRECT_URI:http://localhost:8080/api/integrations/mercadolivre/callback}
mercadolivre.oauth.state-secret=${MELI_STATE_SECRET:${app.crypto.secret}}
mercadolivre.oauth.state-ttl-seconds=${MELI_STATE_TTL_SECONDS:600}
```

Regras:

- `MELI_CLIENT_ID` e `MELI_CLIENT_SECRET` são obrigatórios para OAuth e refresh de token.
- `MELI_REDIRECT_URI` deve ser idêntico ao cadastrado no app do Mercado Livre.
- o fallback local atual aponta para `GET /api/integrations/mercadolivre/callback`
- `MELI_STATE_SECRET` assina o `state` com `HmacSHA256`
- o TTL padrão do `state` é `600` segundos

## Segurança das rotas

Rotas liberadas sem autenticação JWT:

- `GET /api/integrations/mercadolivre/callback`
- `POST /api/integrations/mercadolivre/webhooks/**`

Rotas autenticadas:

- `GET /api/integrations/mercadolivre/connect-url`
- `POST /api/integrations/mercadolivre/exchange`
- `GET /api/integrations/mercadolivre/catalog/**`
- `POST /api/products/{systemClientId}`
- `PUT /api/products/{systemClientId}/{id}`
- `DELETE /api/products/{systemClientId}/{id}`

## Endpoints internos da API

### Autenticação

| Método | Endpoint | Auth | Finalidade |
|---|---|---|---|
| `GET` | `/api/integrations/mercadolivre/connect-url?systemClientId={id}` | JWT | Gera URL de autorização OAuth com `state` assinado |
| `POST` | `/api/integrations/mercadolivre/exchange` | JWT | Troca `code` por tokens e persiste integração validando o `system_client` do usuário autenticado |
| `GET` | `/api/integrations/mercadolivre/callback` | Público | Recebe retorno OAuth direto do Mercado Livre e persiste integração com base no `state` |

### Catálogo e apoio a anúncio

| Método | Endpoint | Auth | Finalidade |
|---|---|---|---|
| `GET` | `/api/integrations/mercadolivre/catalog/categories?systemClientId={id}&siteId=MLB` | JWT | Lista categorias do site |
| `GET` | `/api/integrations/mercadolivre/catalog/categories/suggestions?systemClientId={id}&q={texto}&siteId=MLB` | JWT | Busca sugestão de categoria |
| `GET` | `/api/integrations/mercadolivre/catalog/categories/{categoryId}/attributes?systemClientId={id}` | JWT | Lista atributos da categoria |
| `GET` | `/api/integrations/mercadolivre/catalog/categories/{categoryId}/requirements?systemClientId={id}` | JWT | Retorna atributos, `technical_specs_input` e campos obrigatórios inferidos |

### Fluxos de anúncio

Nao existem endpoints dedicados como `/api/integrations/mercadolivre/listings` no controller atual. A publicação, edição e remoção no Mercado Livre são disparadas pelos endpoints de produto:

| Método | Endpoint | Auth | Efeito no Mercado Livre |
|---|---|---|---|
| `POST` | `/api/products/{systemClientId}` | JWT | Cria produto local e publica anúncio no Mercado Livre |
| `PUT` | `/api/products/{systemClientId}/{id}` | JWT | Atualiza produto local e envia atualização do anúncio no Mercado Livre |
| `DELETE` | `/api/products/{systemClientId}/{id}` | JWT | Desativa produto local e remove anúncio no Mercado Livre |

### Webhooks de vendas

| Método | Endpoint | Auth | Finalidade |
|---|---|---|---|
| `POST` | `/api/integrations/mercadolivre/webhooks/orders` | Público | Recebe notificação de pedidos do Mercado Livre |
| `POST` | `/notifications` | Público | Alias legado que executa a mesma rotina |

## Endpoints externos consumidos no Mercado Livre

### OAuth

| Método | Endpoint externo | Uso |
|---|---|---|
| `GET` | `https://auth.mercadolivre.com.br/authorization` | Início do fluxo OAuth |
| `POST` | `https://api.mercadolibre.com/oauth/token` | Troca `code` por tokens e refresh de token |

### Conta, anúncios e catálogo

| Método | Endpoint externo | Uso |
|---|---|---|
| `GET` | `https://api.mercadolibre.com/users/me` | Resolve `seller_id` do token |
| `GET` | `https://api.mercadolibre.com/users/{sellerId}/items/search` | Lista IDs de anúncios do vendedor |
| `GET` | `https://api.mercadolibre.com/items/{itemId}` | Consulta anúncio |
| `GET` | `https://api.mercadolibre.com/items?ids={id1,id2,...}` | Multiget de anúncios |
| `POST` | `https://api.mercadolibre.com/items` | Cria anúncio |
| `PUT` | `https://api.mercadolibre.com/items/{itemId}` | Atualiza anúncio, fecha anúncio e marca como deletado |
| `POST` | `https://api.mercadolibre.com/items/{itemId}/description` | Cria descrição |
| `PUT` | `https://api.mercadolibre.com/items/{itemId}/description?api_version=2` | Atualiza descrição |
| `POST` | `https://api.mercadolibre.com/pictures/items/upload` | Upload de imagem base64 convertida para multipart |
| `GET` | `https://api.mercadolibre.com/sites/{siteId}/categories` | Lista categorias |
| `GET` | `https://api.mercadolibre.com/sites/{siteId}/domain_discovery/search?q={q}` | Sugestão de categoria |
| `GET` | `https://api.mercadolibre.com/categories/{categoryId}/attributes` | Atributos da categoria |
| `GET` | `https://api.mercadolibre.com/categories/{categoryId}/technical_specs/input` | Requisitos técnicos da categoria |

### Pedidos

| Método | Endpoint externo | Uso |
|---|---|---|
| `GET` | `https://api.mercadolibre.com/orders/{orderId}?x-format-new=true` | Resolve detalhes do pedido a partir do webhook |

## Persistência local

### Tabela `marketplace_integrations`

Usada para armazenar a integração Mercado Livre por `system_client`.

Campos relevantes:

- `system_client_id`
- `marketplace`
- `access_token` criptografado
- `refresh_token` criptografado
- `expires_at`
- `resource`
- `active`

Constraint:

```sql
UNIQUE (system_client_id, marketplace)
```

Metadados gravados em `resource` no OAuth:

```json
{
  "scope": "...",
  "token_type": "bearer",
  "user_id": 123456789
}
```

### Recurso Mercado Livre no produto

Ao publicar, editar ou remover anúncio, o backend persiste `product.resource.mercado_livre` com:

```json
{
  "item_id": "MLB123",
  "permalink": "...",
  "seller_id": 123,
  "status": "active",
  "category_id": "MLBXXXX",
  "listing_type_id": "free",
  "last_updated": "...",
  "raw": {}
}
```

### Vendas

O webhook de pedidos cria ou atualiza registros em:

- `sales`
- `sales_logs`

O vínculo entre pedido do Mercado Livre e venda interna é:

```text
external_reference_id = {orderId}:{itemId}
```

## Fluxo de autenticação OAuth

### Visão geral

O backend suporta duas topologias, definidas pelo valor de `MELI_REDIRECT_URI`:

1. `redirect_uri` apontando para a própria API, usando `GET /api/integrations/mercadolivre/callback`
2. `redirect_uri` apontando para frontend, seguido de `POST /api/integrations/mercadolivre/exchange`

Nos dois casos, a troca de `code` por token usa o mesmo `redirect_uri` configurado.

### Passo a passo

1. O cliente chama `GET /api/integrations/mercadolivre/connect-url?systemClientId={id}`.
2. O backend valida a existência do `system_client`.
3. O backend gera `state` no formato lógico:

```text
systemClientId:expiresAtEpochSeconds:uuid:signature
```

4. A assinatura é gerada com `HmacSHA256` e depois o valor completo é codificado com Base64 URL-safe sem padding.
5. O backend monta a URL:

```text
https://auth.mercadolivre.com.br/authorization?response_type=code&client_id=...&redirect_uri=...&state=...
```

6. O usuário autentica e autoriza o app no Mercado Livre.
7. O Mercado Livre redireciona com `code` e `state`.
8. O backend valida:
- formato do `state`
- assinatura HMAC
- expiração
- `systemClientId` embutido
9. O backend troca `code` por token em `POST /oauth/token`.
10. O backend grava ou atualiza `marketplace_integrations`.

### Validações adicionais no endpoint `/exchange`

Quando o fluxo passa por `POST /api/integrations/mercadolivre/exchange`, o backend ainda:

- identifica o usuário autenticado pelo JWT do OmniSync
- busca o usuário por email
- compara o `systemClientId` do usuário com o `systemClientId` extraído do `state`
- rejeita integração cruzada entre tenants

### Resposta do OAuth persistido

`POST /api/integrations/mercadolivre/exchange` retorna:

- mensagem de sucesso
- `systemClientId`
- marketplace
- status ativo
- `expiresAt`
- `resource`
- `accessToken` em texto puro na resposta

Observação técnica:

- o token é salvo criptografado no banco
- o token é descriptografado apenas para compor a resposta desse endpoint

### Refresh de token

Toda chamada autenticada ao Mercado Livre usa `MarketplaceTokenService`.

Regras:

- se `expires_at` estiver ausente, o token é renovado
- se `expires_at` estiver a menos de `120` segundos, o token é renovado
- o refresh usa `grant_type=refresh_token`
- novos tokens substituem os anteriores em `marketplace_integrations`

## Fluxo de criar anúncio

### Endpoint de entrada

```http
POST /api/products/{systemClientId}
```

### Sequência

1. O backend valida payload do produto.
2. O produto é salvo localmente com `active=true`.
3. `ProductService.create` chama `MercadoLivreListingService.createListing`.
4. O backend obtém `access_token` válido da integração ativa.
5. O backend lê `product.resource.mercado_livre`.
6. Os campos mínimos esperados em `resource` para criação são:
- `category_id`
- `condition`
- `pictures`
- `attributes`
7. Se uma imagem vier com `base64`, o backend faz upload prévio em `/pictures/items/upload`.
8. O payload enviado para `POST /items` é montado com as regras atuais:

```json
{
  "site_id": "MLB",
  "title": "Item de Teste - Por Favor Nao Ofertar",
  "category_id": "<resource.mercado_livre.category_id>",
  "price": "<product.price>",
  "currency_id": "BRL",
  "available_quantity": "stock - reservedStock",
  "buying_mode": "buy_it_now",
  "listing_type_id": "free",
  "condition": "<resource.mercado_livre.condition>",
  "pictures": [],
  "attributes": [],
  "seller_custom_field": "<product.sku>"
}
```

9. Após criação do item, o backend faz upsert da descrição:
- tenta `PUT /items/{itemId}/description?api_version=2`
- se receber `404`, faz fallback para `POST /items/{itemId}/description`
10. O backend persiste o retorno do Mercado Livre em `product.resource.mercado_livre`.

### Restrições e comportamento

- `available_quantity = stock - reservedStock`
- se `reservedStock > stock`, a criação falha
- se `attributes` não contiver `SELLER_SKU`, o backend injeta esse atributo com o `sku`
- o título atual de criação é fixo e não usa `product.name`
- o fluxo atual força ambiente de teste com `site_id=MLB`, `currency_id=BRL` e `listing_type_id=free`

## Fluxo de editar anúncio

### Endpoint de entrada

```http
PUT /api/products/{systemClientId}/{id}
```

### Sequência

1. O backend carrega o produto ativo local.
2. O `item_id` do anúncio é extraído de `product.resource.mercado_livre.item_id`.
3. O recurso recebido no payload é mesclado com o recurso atual, preservando `item_id`.
4. O produto local é salvo.
5. `ProductService.update` chama `MercadoLivreListingService.updateListing`.
6. O backend monta o payload para `PUT /items/{itemId}` com:

```json
{
  "title": "<product.name>",
  "price": "<product.price>",
  "available_quantity": "stock - reservedStock",
  "status": "<opcional, vindo de resource>",
  "listing_type_id": "<opcional, vindo de resource>",
  "condition": "<opcional, vindo de resource>",
  "buying_mode": "<opcional, vindo de resource>",
  "video_id": "<opcional, vindo de resource>",
  "pictures": "<opcional, vindo de resource>",
  "shipping": "<opcional, vindo de resource>",
  "sale_terms": "<opcional, vindo de resource>",
  "variations": "<opcional, vindo de resource>",
  "attributes": []
}
```

7. A descrição é atualizada pelo mesmo mecanismo de upsert.
8. O retorno do Mercado Livre substitui o snapshot salvo em `product.resource.mercado_livre`.

### Regras específicas

- `listing_type_id` opcional não pode ser `gold` nem `gold_special` no fluxo atual
- `pictures` pode conter `source`, `id` ou `base64`
- imagens em `base64` passam por upload antes do `PUT /items/{itemId}`
- `SELLER_SKU` é garantido na lista de atributos

## Fluxo de deletar anúncio

### Endpoint de entrada

```http
DELETE /api/products/{systemClientId}/{id}
```

### Sequência

1. O backend busca o produto ativo local.
2. O produto é marcado localmente com `active=false`.
3. `ProductService.delete` chama `MercadoLivreListingService.deleteListing`.
4. O backend lê `item_id` de `product.resource.mercado_livre.item_id`.
5. O backend consulta o item atual em `GET /items/{itemId}`.
6. Se o item ainda nao estiver em condição de pular fechamento, envia:

```json
PUT /items/{itemId}
{
  "status": "closed"
}
```

7. Em seguida o backend tenta remoção lógica:

```json
PUT /items/{itemId}
{
  "deleted": true
}
```

8. Se o Mercado Livre responder `409`, o backend espera `2` segundos e tenta novamente uma vez.
9. O retorno final do item é persistido em `product.resource.mercado_livre`.

### Quando o passo de fechamento é pulado

O backend nao faz `status=closed` quando:

- o item já está com `status=closed`
- o item está `under_review` com `sub_status=forbidden`

## Fluxo de receber notificação de vendas

### Endpoint de entrada

```http
POST /api/integrations/mercadolivre/webhooks/orders
```

Payload esperado:

```json
{
  "resource": "/orders/2001",
  "user_id": 123456789,
  "topic": "orders_v2",
  "application_id": 999999,
  "attempts": 1,
  "sent": "2026-04-14T10:00:00.000Z",
  "received": "2026-04-14T10:00:01.000Z"
}
```

### Sequência

1. O backend valida `user_id`, `resource` e `topic`.
2. Apenas `topic=orders_v2` é processado.
3. O `orderId` é extraído do sufixo de `resource`.
4. O backend localiza a integração ativa por `resource.user_id` em `marketplace_integrations.resource->>'user_id'`.
5. O backend obtém `access_token` válido do `system_client` dono da integração.
6. O backend consulta o pedido em:

```http
GET /orders/{orderId}?x-format-new=true
```

7. O backend percorre `order_items`.
8. Para cada linha do pedido:
- resolve `item.id`
- busca `Product` ativo por `systemClientId + mercadoLivreItemId`
- monta `external_reference_id = {orderId}:{itemId}`
- cria ou atualiza `Sale`
- ajusta estoque conforme transição de status
- registra log em `sales_logs`

### Mapeamento de status da venda

Regra atual:

- `CANCELLED` quando `order.status` contém `cancel`
- `CONFIRMED` quando `order.status` é `paid` ou `confirmed`
- `CONFIRMED` quando a lista `tags` contém `paid`
- qualquer outro caso vira `PENDING`

### Regra de estoque

O estoque só é comprometido quando a venda está `CONFIRMED`.

Na prática:

- criação de venda confirmada reduz estoque
- mudança de `PENDING` para `CONFIRMED` reduz estoque
- mudança de `CONFIRMED` para `CANCELLED` recompõe estoque
- mudança entre estados com mesmo comprometimento recalcula apenas se necessário

### Resposta do webhook

Quando processado com sucesso, o endpoint retorna:

```json
{
  "processed": true,
  "order_id": "2001",
  "sale_status": "CONFIRMED",
  "items_processed": 1,
  "lines": []
}
```

Quando o tópico nao é suportado:

```json
{
  "processed": false,
  "reason": "unsupported_topic",
  "topic": "..."
}
```

## Observações técnicas importantes

- Usuário de testes:
```
Vendedor
{
    "id": 3328019238,
    "email": "test_user_3706049556946734631@testuser.com",
    "nickname": "TESTUSER3706049556946734631",
    "site_status": "active",
    "password": "IHU9rW8afD"
}

Comprador
{
    "id": 3328019240,
    "email": "test_user_5126625446249747716@testuser.com",
    "nickname": "TESTUSER5126625446249747716",
    "site_status": "active",
    "password": "uqOtKkre3q"
}
```

- o backend atual possui métodos internos para listagem e consulta de anúncios em `MercadoLivreListingService`, mas esses métodos nao estão expostos em controller HTTP
- o alias `POST /notifications` executa exatamente o mesmo fluxo do webhook principal
- o callback OAuth público persiste integração apenas com base no `state`; o endpoint `/exchange` é o único que cruza explicitamente o `state` com o usuário autenticado
- a criação de anúncio hoje usa título fixo de teste, enquanto a edição usa `product.name`
- a remoção de anúncio é lógica no Mercado Livre, via `deleted=true`, após tentativa de fechamento
