# Alteração e redefinição de senha

As rotas utilizam a autenticação existente por cookie `ACCESS_TOKEN` e não alteram
papéis, permissões, situação ativa ou empresa do usuário. Não há migration nova
nem mudança no front-end neste card.

## Alterar a própria senha

`PUT /api/users/me/password`

```json
{
  "current_password": "SenhaAtual123",
  "new_password": "NovaSenha456",
  "new_password_confirmation": "NovaSenha456"
}
```

Qualquer usuário autenticado pode alterar sua senha, sem `USER_MANAGE`.
A senha atual é obrigatória e validada com o hash armazenado.

## Redefinir a senha de outro usuário

`PUT /api/users/{userId}/password`

```json
{
  "new_password": "SenhaTemporaria456",
  "new_password_confirmation": "SenhaTemporaria456"
}
```

Exige a permissão efetiva `USER_MANAGE`, conforme o modelo relacional existente;
não basta o nome do papel ser `ADMIN`. O alvo deve pertencer à mesma empresa.
Esta rota não aceita o próprio usuário: nesse caso deve ser usada a rota `/me/password`
com a senha atual. Não há envio automático da nova senha por e-mail nem obrigação
de troca no próximo login.

Ambas as rotas retornam `204 No Content` no sucesso. Erros usam `ErrorResponse`:

- `400`: campos ausentes, senha atual incorreta, confirmação divergente ou política inválida.
- `401`: sem autenticação válida.
- `403`: sem `USER_MANAGE` na operação administrativa.
- `404`: alvo inexistente ou pertencente a outra empresa, com a mesma mensagem.

## Política e recuperação

A regra existente de 6 a 100 caracteres não vazios é preservada, com validação
explícita do limite técnico do BCrypt: no máximo **72 bytes UTF-8**. Caracteres
acentuados podem ocupar mais de um byte. Senhas não são aparadas ou normalizadas.
A confirmação deve ser exatamente igual à nova senha. Somente o hash BCrypt é salvo.

Após uma troca, todos os links de recuperação pendentes do usuário são invalidados.
O fluxo existente `POST /api/auth/reset-password` mantém seu payload e sucesso `200`;
links usados, expirados, inexistentes ou de usuário inativo retornam o mesmo erro seguro.

As operações bloqueiam primeiro a linha do usuário e depois acessam os tokens,
sempre dentro de uma transação. O estado é recarregado após o bloqueio para impedir
que duas requisições utilizem a mesma senha antiga ou o mesmo link simultaneamente.
A emissão de links segue a mesma ordem de bloqueio.

**Sessões JWT já emitidas não são revogadas por esta implementação.** A alteração
invalida links de recuperação, não access/refresh tokens. Revogação global de sessões
exige um mecanismo próprio e fica fora deste card.

## Auditoria

Eventos `UPDATE` / `USER` identificam o alvo por `entity_id`, o executor pelos campos
`user_*` e a empresa por `system_client_id`. `previous_data` e `new_data` são nulos.
Os metadados contêm somente `changed_fields: ["credential"]` e a origem:

- `PASSWORD_CHANGE`: troca própria.
- `ADMIN_PASSWORD_RESET`: redefinição por outro usuário autorizado.
- `PASSWORD_RESET`: recuperação por e-mail.

Não são enviados senha, hash ou token à auditoria. A auditoria participa da mesma
transação da senha e dos tokens: uma falha desfaz todos esses efeitos.

## Testes

```sh
./gradlew test --tests '*PasswordManagementIntegrationTest'
./gradlew test
```

Os testes de integração usam PostgreSQL temporário local, sem Docker e sem conectar
à VM. Cobrem autenticação, login após troca, confirmação, política, permissões,
isolamento, auditoria sem credenciais, rollback e concorrência.
