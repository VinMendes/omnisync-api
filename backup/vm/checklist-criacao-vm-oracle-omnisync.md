# Checklist — Criação/Recriação da VM Oracle para o OmniSync

> Objetivo: ter um passo a passo para reconstruir uma VM Oracle do zero, configurar rede/firewall, Nginx, Certbot/SSL e subir o projeto OmniSync novamente.

---

## 1. Conferir capacidade e cobrança da VM Oracle

Antes de criar ou redimensionar a VM, conferir se ela está dentro do limite gratuito/seguro.

### Conferir o tipo da conta

No painel da Oracle:

```txt
Billing and Cost Management
→ Subscription / Payment Management
→ Verificar se está como Pay As You Go
```

Se estiver como **Pay As You Go**, cuidado: recursos acima do Always Free podem gerar cobrança.

### Conferir limites atuais

No painel da Oracle:

```txt
Governance & Administration
→ Limits, Quotas and Usage
→ Compute
```

Verificar principalmente:

```txt
Ampere A1 / VM.Standard.A1.Flex
OCPU limit
Memory limit
```

### Configuração segura sugerida

Para evitar cobrança no Always Free novo:

```txt
Shape: VM.Standard.A1.Flex
OCPUs: 2
Memory: 12 GB RAM
```

Evitar deixar em:

```txt
4 OCPUs / 24 GB RAM
```

a menos que tenha certeza de que não será cobrado.

### Conferir custos

No painel:

```txt
Billing and Cost Management
→ Cost Analysis
```

Verificar se está aparecendo custo.

### Criar orçamento/alerta

No painel:

```txt
Billing and Cost Management
→ Budgets
```

Sugestão:

```txt
Budget mensal: R$ 5,00
Alertas: 50%, 80% e 100%
```

Observação: orçamento/alerta não bloqueia cobrança automaticamente. Ele só avisa.

---

## 2. Criar a VM na Oracle

No painel da Oracle:

```txt
Compute
→ Instances
→ Create Instance
```

Sugestão:

```txt
Name: projeto-aplicado ou omnisync
Image: Ubuntu
Shape: VM.Standard.A1.Flex
OCPUs: 2
Memory: 12 GB RAM
Boot volume: conferir para não ultrapassar limite gratuito
```

Salvar a chave SSH privada com cuidado.

Conectar na VM:

```bash
ssh -i caminho/da/chave.key ubuntu@IP_DA_VM
```

---

## 3. Liberar portas na Oracle

No painel da Oracle, ir até a rede da VM:

```txt
Compute
→ Instance
→ Attached VNIC
→ Subnet
→ Security List ou Network Security Group
```

Liberar as portas necessárias:

```txt
22    SSH
80    HTTP
443   HTTPS
8080  Aplicação Spring, se necessário para teste externo
```

Regras típicas de entrada:

```txt
Source CIDR: 0.0.0.0/0
Protocol: TCP
Destination Port: 22
```

```txt
Source CIDR: 0.0.0.0/0
Protocol: TCP
Destination Port: 80
```

```txt
Source CIDR: 0.0.0.0/0
Protocol: TCP
Destination Port: 443
```

Para produção, evitar expor `8080` publicamente se o Nginx já faz proxy para `127.0.0.1:8080`.

---

## 4. Atualizar a VM

Na VM:

```bash
sudo apt update
sudo apt upgrade -y
```

Instalar pacotes úteis:

```bash
sudo apt install curl wget git nano unzip htop net-tools -y
```

---

## 5. Configurar firewall interno da VM

### Ver regras atuais do iptables

```bash
sudo iptables -L INPUT -n -v
```

Ver todas as regras:

```bash
sudo iptables -L -n -v
```

Backup das regras:

```bash
sudo iptables-save | tee iptables-backup.rules > /dev/null
```

### Liberar portas no iptables

Se necessário:

```bash
sudo iptables -I INPUT -p tcp --dport 22 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
```

Opcional, apenas para teste externo:

```bash
sudo iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
```

### Salvar regras do iptables

Instalar persistência:

```bash
sudo apt install iptables-persistent -y
```

Salvar regras:

```bash
sudo netfilter-persistent save
```

Recarregar regras:

```bash
sudo netfilter-persistent reload
```

### Conferir UFW

```bash
sudo ufw status verbose
```

Se usar UFW:

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

---

## 6. Instalar Java e dependências do projeto

Conferir versão do Java necessária para o Spring Boot.

Exemplo com Java 17:

```bash
sudo apt install openjdk-17-jdk -y
```

Conferir:

```bash
java -version
```

Instalar Maven, se necessário:

```bash
sudo apt install maven -y
```

Conferir:

```bash
mvn -version
```

---

## 7. Clonar ou enviar o projeto para a VM

Clonar pelo Git:

```bash
git clone URL_DO_REPOSITORIO
cd NOME_DO_PROJETO
```

Ou enviar via SCP/SFTP, se necessário.

Conferir arquivos importantes de backup no projeto:

```txt
backups/
anotacoes-criacao.md
nginx/omnisync
start-omnisync.sh
iptables-backup.rules
```

---

## 8. Configurar variáveis e arquivos do projeto

Criar ou ajustar arquivos necessários:

```txt
application.properties
application.yml
.env
```

Conferir configurações importantes:

```txt
porta da aplicação: 8080
conexão com MongoDB
usuários/senhas
URLs externas
domínio
```

Nunca commitar senhas reais ou chaves privadas no Git.

---

## 9. Rodar o projeto manualmente para testar

Exemplo com Maven:

```bash
mvn clean package
```

Rodar o `.jar`:

```bash
java -jar target/NOME_DO_ARQUIVO.jar
```

Testar localmente na VM:

```bash
curl http://127.0.0.1:8080
```

Se responder, a aplicação está rodando localmente.

---

## 10. Instalar e configurar Nginx

Instalar:

```bash
sudo apt install nginx -y
```

Conferir status:

```bash
sudo systemctl status nginx
```

Criar arquivo do site:

```bash
sudo nano /etc/nginx/sites-available/omnisync
```

Configuração temporária inicial, antes do SSL:

```nginx
server {
    listen 80;
    listen [::]:80;

    server_name omnisync.site www.omnisync.site;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Ativar site:

```bash
sudo ln -s /etc/nginx/sites-available/omnisync /etc/nginx/sites-enabled/omnisync
```

Se necessário, remover default:

```bash
sudo rm /etc/nginx/sites-enabled/default
```

Testar Nginx:

```bash
sudo nginx -t
```

Recarregar:

```bash
sudo systemctl reload nginx
```

---

## 11. Apontar DNS do domínio

No provedor de domínio/DNS:

```txt
A record:
omnisync.site → IP_DA_VM

A record:
www.omnisync.site → IP_DA_VM
```

Aguardar propagação.

Testar:

```bash
ping omnisync.site
```

ou:

```bash
dig omnisync.site
```

---

## 12. Instalar Certbot e gerar SSL

Instalar Certbot:

```bash
sudo apt install certbot python3-certbot-nginx -y
```

Gerar certificado:

```bash
sudo certbot --nginx -d omnisync.site -d www.omnisync.site
```

O Certbot deve editar o Nginx e adicionar:

```nginx
listen 443 ssl;
ssl_certificate /etc/letsencrypt/live/omnisync.site/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/omnisync.site/privkey.pem;
```

Testar renovação automática:

```bash
sudo certbot renew --dry-run
```

Ver certificados:

```bash
sudo certbot certificates
```

---

## 13. Colocar configuração final do Nginx

Depois do Certbot, conferir o arquivo:

```bash
sudo cat /etc/nginx/sites-available/omnisync
```

A configuração final deve ter:

```txt
porta 443 com SSL
redirecionamento HTTP → HTTPS
proxy_pass para 127.0.0.1:8080
domínios omnisync.site e www.omnisync.site
```

Testar:

```bash
sudo nginx -t
```

Recarregar:

```bash
sudo systemctl reload nginx
```

---

## 14. Rodar script de start do OmniSync

Dar permissão ao script:

```bash
chmod +x start-omnisync.sh
```

Rodar:

```bash
./start-omnisync.sh
```

Se o script tiver outro nome:

```bash
chmod +x start-omnisync.sh
./start-omnisync.sh
```

Conferir se a aplicação subiu:

```bash
ss -tulpn
```

ou:

```bash
curl http://127.0.0.1:8080
```

---

## 15. Conferir portas abertas

```bash
ss -tulpn
```

Verificar se aparecem:

```txt
:80
:443
:8080
```

Testar domínio:

```bash
curl -I http://omnisync.site
curl -I https://omnisync.site
```

---

## 16. Conferir logs

Logs do Nginx:

```bash
sudo tail -f /var/log/nginx/access.log
```

```bash
sudo tail -f /var/log/nginx/error.log
```

Logs da aplicação dependem de como o script foi configurado.

Se usar `nohup`:

```bash
tail -f nohup.out
```

Se usar arquivo próprio:

```bash
tail -f logs/omnisync.log
```

---

## 17. Opcional: criar serviço systemd

Criar arquivo:

```bash
sudo nano /etc/systemd/system/omnisync.service
```

Exemplo base:

```ini
[Unit]
Description=OmniSync Spring Boot Application
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/NOME_DO_PROJETO
ExecStart=/usr/bin/java -jar /home/ubuntu/NOME_DO_PROJETO/target/NOME_DO_ARQUIVO.jar
SuccessExitStatus=143
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Ativar:

```bash
sudo systemctl daemon-reload
sudo systemctl enable omnisync
sudo systemctl start omnisync
```

Ver status:

```bash
sudo systemctl status omnisync
```

---

## 18. Checklist final de validação

Conferir:

```txt
[ ] VM está dentro do limite seguro da Oracle: 2 OCPUs / 12 GB RAM
[ ] Billing/Cost Analysis está sem cobrança inesperada
[ ] Budget/alerta está ativo
[ ] Portas 80 e 443 liberadas na Oracle
[ ] Portas 80 e 443 liberadas no firewall interno
[ ] Nginx instalado
[ ] Certbot instalado
[ ] Domínio apontando para IP da VM
[ ] SSL gerado com sucesso
[ ] Nginx testado com sudo nginx -t
[ ] Aplicação rodando na porta 8080
[ ] Nginx fazendo proxy para 127.0.0.1:8080
[ ] Site abre em https://omnisync.site
[ ] Script start-omnisync.sh salvo no projeto
[ ] Configuração do Nginx salva no projeto
[ ] Regras iptables/ufw salvas no projeto
```

---

## 19. Comandos úteis de emergência

Ver status Nginx:

```bash
sudo systemctl status nginx
```

Reiniciar Nginx:

```bash
sudo systemctl restart nginx
```

Testar configuração:

```bash
sudo nginx -t
```

Ver configuração completa carregada pelo Nginx:

```bash
sudo nginx -T
```

Ver portas abertas:

```bash
ss -tulpn
```

Ver regras INPUT do iptables:

```bash
sudo iptables -L INPUT -n -v
```

Ver regras completas restauráveis:

```bash
sudo iptables-save
```

Ver certificados:

```bash
sudo certbot certificates
```

Renovar SSL manualmente:

```bash
sudo certbot renew
```

Testar renovação SSL:

```bash
sudo certbot renew --dry-run
```

---

## Observação importante

Em uma VM nova, não colar diretamente a configuração final com SSL se os arquivos de certificado ainda não existem em `/etc/letsencrypt`.

Primeiro configurar Nginx na porta 80, gerar o certificado com Certbot, e só depois deixar a configuração final com HTTPS.

