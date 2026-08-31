# Autenticação e contrato de permissões

Esta entrega conclui a base de autenticação Spring e o modelo tipado de permissões.
Ela **não aplica autorização por operação nem isolamento de dados entre empresas**.
As rotas continuam com as regras anteriores de `permitAll`/`authenticated`; mapear
uma permission no principal não protege um endpoint automaticamente.

## Autenticação

- O bean `authenticationManager` em `SecurityBeansConfig` instancia um `ProviderManager`.
- Ele usa o `DaoAuthenticationProvider` do Spring, com BCrypt e `CustomUserDetailsService`.
- `OmniUserPrincipal` contém usuário, empresa, status e authorities, sem expor a entidade JPA.
- O filtro JWT e o refresh validam assinatura/expiração/tipo e recarregam a conta do banco.
- Usuário ou empresa desativados deixam de autenticar, inclusive com tokens já emitidos.
- O hash de senha é apagado do principal depois da autenticação.
- Credenciais inválidas retornam `401`; indisponibilidade interna do provider no login retorna `503`.

O filtro JWT próprio permanece. O `sub` continua sendo o e-mail, preservando tokens
existentes e consumidores de `Authentication.getName()`. Não há roles no JWT:
alterações no `UserResource` são lidas na próxima requisição, sem reemitir o token.

Login/registro mantêm os campos anteriores da resposta e os cookies HttpOnly.
O access cookie continua com 15 minutos e o refresh cookie com sete dias. A validade
do JWT continua configurável (20 minutos para access na configuração atual); alinhar
os prazos dos cookies a essa configuração é uma melhoria separada.

## Fonte de verdade

O campo `users.resource` continua sendo JSONB. Não há novas tabelas nem vínculo com
as antigas tabelas `roles`/`user_roles` previstas na V1; elas permanecem intocadas.

`UserResource` é um record com `Role`, `Set<Permission>` e os demais metadados.
Hibernate serializa o contrato canônico, por exemplo:

```json
{
  "theme": "dark",
  "role": "VIEWER",
  "permissions": ["PRODUCT_READ", "SALE_READ"]
}
```

O catálogo é fechado nos enums `Role` e `Permission`. O mapa imutável
`Role.ROLE_DEFAULT_PERMISSIONS` define os padrões iniciais, não permissões obrigatórias:
uma lista explícita pode personalizar o conjunto. A autorização para quem pode
atribuir ou alterar esses valores será implementada na próxima etapa.

## Matriz inicial

| Permission | ADMIN | MANAGER | SELLER | VIEWER |
| --- | --- | --- | --- | --- |
| `PRODUCT_READ` | Sim | Sim | Sim | Sim |
| `PRODUCT_WRITE` | Sim | Sim | — | — |
| `LISTING_PUBLISH` | Sim | Sim | Sim | — |
| `SALE_READ` | Sim | Sim | Sim | Sim |
| `SALE_WRITE` | Sim | Sim | Sim | — |
| `USER_MANAGE` | Sim | — | — | — |
| `INTEGRATION_MANAGE` | Sim | — | — | — |
| `SETTINGS_MANAGE` | Sim | — | — | — |

As authorities são `ROLE_ADMIN`/`ROLE_MANAGER`/`ROLE_SELLER`/`ROLE_VIEWER` mais
os códigos das permissões do usuário. São apenas informações de autorização nesta etapa.

## Entrada e compatibilidade com o frontend

Cadastro e atualização aceitam `role` e `permissions` no topo, ou dentro de `resource`
como no frontend atual. Se os dois locais forem informados, devem ser equivalentes;
valores conflitantes retornam `400`.

- Role desconhecida, como `superuser`, retorna `400` com mensagem clara.
- Permission desconhecida ou uma lista malformada em entrada nova retorna `400`.
- Sem permissions no cadastro, aplicam-se os padrões do papel; sem role, usa-se `VIEWER`.
- Uma lista vazia explícita representa nenhuma permission, sem cair nos padrões.
- Atualizar apenas metadados preserva role e permissions. Trocar a role sem informar
  permissions aplica os padrões do novo papel.
- Metadados fora de role/permissions são preservados, inclusive valores nulos.

Para evitar uma alteração imediata do frontend, nomes são aceitos sem diferença de
maiúsculas/minúsculas e **`editor` é um alias legado de `SELLER`**, não um quinto papel.
Essa é uma compatibilidade de nomenclatura; a matriz acima define o papel canônico.

Os rótulos legados conhecidos em `src/lib/userResource.ts` são convertidos para códigos:
por exemplo, `Gestão de estoque` equivale a `PRODUCT_READ` + `PRODUCT_WRITE`,
`Anúncios` a `PRODUCT_READ` + `LISTING_PUBLISH`, `Vendas` a `SALE_READ` + `SALE_WRITE`,
e `Acesso total` ao catálogo inicial completo. Textos arbitrários não são aceitos.

`UserResponse`, inclusive em `GET /api/users/me`, expõe `role` e `permissions` canônicos
no topo, mantendo `resource` como projeção de compatibilidade (papel minúsculo e
rótulos antigos quando representam exatamente o mesmo conjunto). Um subconjunto
que não tenha rótulo equivalente mantém o código canônico, para não ampliar o acesso
quando a interface enviar os dados de volta.

O frontend poderá migrar depois para os campos explícitos. Seu helper atual interpreta
lista vazia como padrões do papel; portanto, a apresentação de conjuntos vazios deve
ser ajustada nessa próxima etapa. A lista canônica no topo é a referência exata.

## Dados legados e migração

`V7__type_user_resource_permissions.sql` altera somente `users.resource`:

1. Preserva roles válidas e normaliza sua nomenclatura, incluindo `editor` → `SELLER`.
2. Sem role válida, atribui `ADMIN` ao primeiro usuário da empresa e `VIEWER` aos demais.
   Primeiro significa menor `(created_at, id)`, independentemente do status ativo.
3. Sem permissions, aplica o mapa padrão. Uma lista explícita vazia continua vazia.
4. Normaliza rótulos legados conhecidos; descarta entradas desconhecidas ou malformadas.
5. Preserva os outros metadados. Um resource que não era objeto fica em `_legacy_resource`.

O backfill segue o card e substitui a ideia anterior de promover todos a ADMIN.
Ele ocorre uma única vez pelo Flyway, sem trigger ou concessão automática de ADMIN
a cadastros futuros. Roles/permissões alteradas depois não são restauradas a cada startup.

A leitura do JSONB também é tolerante: role ausente/desconhecida usa `VIEWER`,
dados malformados não geram authorities arbitrárias nem derrubam login ou `/me`.
Essa tolerância é somente para leitura legada; entradas novas continuam sendo validadas.

A consulta somente leitura em
`src/main/resources/db/verification/verify_user_resource_permissions.sql`
deve retornar zero linhas após o backfill e gravações pelo contrato tipado.

**A migração não foi executada na VM compartilhada.** Como o Flyway está ativo,
iniciar esta versão apontando para a VM executará a V7; coordenar essa execução
antes de rodar a aplicação com as variáveis do banco compartilhado.

## Testes

```sh
./gradlew test
```

Os testes usam valores sintéticos e, para integração, PostgreSQL descartável local
via [Embedded Postgres](https://github.com/zonkyio/embedded-postgres). Não requerem
Docker, não usam `DB_URL` da VM e não enviam e-mails nem chamam Mercado Livre.
O banco temporário é encerrado ao final. Java 25 continua sendo o requisito do projeto.

A cobertura inclui provider real/BCrypt, cookies/JWT/refresh, desativação de contas,
serialização com Jackson 2 e 3, persistência JSONB real, validação HTTP, matriz padrão,
backfill desde V6, consulta de verificação e preservação das atribuições em uma segunda
execução do Flyway. Os subconjuntos possíveis do catálogo são testados no round-trip legado.

## Próxima etapa, fora desta entrega

- Proteger atribuição/alteração de roles e permissions; validar o administrador que faz a operação.
- Separar cadastro inicial, convite e criação administrativa de contas. O frontend atual
  cria usuários sem enviar a sessão (`credentials: 'omit'`), o que exigirá coordenação.
- Aplicar as permissões aos serviços/endpoints e limitar todas as operações à empresa do principal.
- Revisar rotas públicas, CSRF/cookies de produção e revogação/rotação de refresh tokens.
- Avaliar Resource Server em uma alteração separada, preservando cookies e carregamento do usuário.

Nenhum PR ou merge faz parte desta entrega.
