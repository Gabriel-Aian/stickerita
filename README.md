# Stickerita

Crie pacotes de figurinhas a partir das suas fotos e envie direto para o WhatsApp, sem servidor, sem conta e sem internet.

O Stickerita faz o mesmo caminho dos apps de figurinha conhecidos: converte cada imagem para o formato do WhatsApp e entrega o pacote pelo mecanismo **oficial** de *sticker packs*.

## O que ele faz

- Escolhe várias imagens da galeria de uma vez.
- Converte cada uma para **WebP 512×512 com transparência e menos de 100 KB**, corrigindo a rotação das fotos.
- Todas as imagens são redimensionadas para exatamente 512×512, sem manter a proporção (esticadas ou comprimidas, mesmo as menores que 512).
- Monta o pacote (de 3 a 30 figurinhas), gera o ícone de 96×96 e permite escolher até 3 emojis por figurinha.
- **Figurinhas animadas**: crie um pacote animado e use GIFs ou vídeos. Você escolhe o trecho de até 10 s (início e fim, com prévia dos quadros). Cada um vira um WebP animado 512×512 de até 500 KB; se for pesado, o app reduz sozinho a qualidade, a resolução de trabalho e a taxa de quadros, nessa ordem de preferência, até caber, e avisa o que reduziu. O arquivo final continua 512×512.
- **Compartilhar direto para o Stickerita**: em qualquer app (WhatsApp, galeria, navegador), compartilhe uma imagem, GIF ou vídeo, escolha o Stickerita e uma janela rápida deixa você escolher ou criar o pacote, sem abrir o app.
- **Posts do X**: no X, use *Compartilhar → Stickerita* no post. O app lista as fotos, GIFs e vídeos dele, você escolhe quais viram figurinha e o resto segue o fluxo normal.
- Depois de adicionar pelo Compartilhar, o app já pede ao WhatsApp para atualizar o pacote (quando ele tem 3 figurinhas ou mais). O WhatsApp sempre pede a sua confirmação.
- Botão **Adicionar ao WhatsApp** (ou WhatsApp Business) usando o contrato oficial.
- Suas imagens e pacotes ficam só no aparelho e nunca são enviados a lugar nenhum.
- **Atualiza sozinho pelo GitHub**: ao abrir, o app confere a última release e, havendo versão nova, mostra um aviso com o botão *Atualizar agora*. O botão de atualizar na barra superior força a verificação.

## Instalar

1. Abra a aba **Releases** deste repositório no celular e baixe o `Stickerita-vX.Y.Z.apk`.
2. Abra o arquivo. Se o Android pedir, permita a instalação de apps desta fonte.
3. Requer Android 8.0 ou superior.

## Como usar

1. **Novo pacote** → dê um nome e um autor (ligue *Figurinhas animadas* para um pacote de GIFs e vídeos).
2. Toque no **+** e escolha as imagens (ou GIFs e vídeos, no pacote animado).
3. Com pelo menos 3 figurinhas, toque em **Adicionar ao WhatsApp**.

Toque em uma figurinha para editar os emojis ou removê-la.

**Atalho:** em qualquer app, use **Compartilhar → Stickerita** numa imagem, GIF ou vídeo. Escolha o pacote (ou crie um novo) e toque em **Adicionar**. Imagens comuns vão para pacotes estáticos; GIFs e vídeos, para pacotes animados.

## Limites (vêm do WhatsApp)

| Item | Regra |
|---|---|
| Figurinha | WebP estático, 512×512, até 100 KB |
| Figurinha animada | WebP animado, 512×512, até 500 KB, até 10 s |
| Mistura | um pacote é só estático ou só animado |
| Ícone do pacote | 96×96, até 50 KB (gerado automaticamente) |
| Pacote | de 3 a 30 figurinhas |
| Emojis | até 3 por figurinha |

As figurinhas animadas só se mexem na pré-visualização do app a partir do Android 9; o WhatsApp as anima normalmente.

## Atualizações

O app só usa a rede para duas coisas: consultar a última release e baixar o APK no GitHub (`api.github.com` e `github.com`), e, **somente quando você compartilha um link de post do X**, ler as mídias do post. Nesse caso o número do post é enviado ao serviço público [FxEmbed](https://docs.fxembed.com/api/introduction) (`api.fxtwitter.com`) e as mídias são baixadas direto dos servidores do X (`twimg.com`). Suas imagens e figurinhas nunca são enviadas a lugar nenhum. Por isso o app pede as permissões de internet e de instalar apps; na primeira atualização o Android pede para você liberar a instalação por este app. O instalador do Android sempre pede a sua confirmação.

O Android só aceita a atualização se o APK novo for assinado com a mesma chave do instalado e tiver `versionCode` maior. Para publicar uma versão:

1. Aumente `versionCode` e `versionName` em `app/build.gradle.kts`.
2. Faça o push em `main`.
3. Crie uma release com a tag `v<versionName>` (ex.: `v0.1.3`). O workflow compila e anexa o APK.

## Compilar

Pré-requisitos: JDK 17 e Android SDK (API 35).

```bash
./gradlew assembleRelease
# APK em app/build/outputs/apk/release/
```

Sem as variáveis de assinatura abaixo, o build de release usa a chave de debug.

| Variável | Conteúdo |
|---|---|
| `STICKERITA_KEYSTORE` | caminho do arquivo `.jks` |
| `STICKERITA_KEYSTORE_PASSWORD` | senha do keystore |
| `STICKERITA_KEY_ALIAS` | alias da chave |
| `STICKERITA_KEY_PASSWORD` | senha da chave |

Cada tag `v*` enviada ao GitHub dispara o workflow `Build APK`, que compila, assina e publica a release.

## Estrutura

```
app/src/main/java/io/github/gabrielaian/stickerita/
├── MainActivity.kt           telas (Jetpack Compose)
├── ShareActivity.kt          janela rápida do "Compartilhar" e escolha de mídias de posts do X
├── TweetLoader.kt            lê e baixa as mídias de um post do X
├── WhatsApp.kt               intent de adicionar pacote ao WhatsApp
├── MainViewModel.kt          estado e ações
├── StickerConverter.kt       imagem → WebP 512×512 e ícone 96×96
├── AnimatedConverter.kt      GIF ou vídeo → quadros e WebP animado
├── WebPAnimMuxer.kt          monta o contêiner do WebP animado
├── PackRepository.kt         armazenamento local dos pacotes
├── StickerContentProvider.kt contrato de sticker packs do WhatsApp
├── Updater.kt                verificação e download de atualizações
└── Emoji.kt                  tratamento dos emojis
```

## Ideias para depois

- Remover fundo automaticamente.
- Exportar e importar pacotes.

## Aviso

Projeto independente, sem vínculo com o WhatsApp ou a Meta. "WhatsApp" é marca de seus respectivos titulares.

## Licença

[MIT](LICENSE)
