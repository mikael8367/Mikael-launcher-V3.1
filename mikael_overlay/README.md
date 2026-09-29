# Mikael Launcher V3.1 overlay

Camada de interface própria do Mikael Launcher sobre o motor funcional do PojavLauncher.

Recursos principais:
- Layout dedicado a Landscape e Portrait.
- Cards, navegação e animações de toque.
- Seleção real de versões a partir do manifesto de versões.
- `JOGAR` chama `MoJsonDownloader` + `ContextAwareDoneListener` + `GameActivity` do Pojav.
- Contas Microsoft, Ely.by e Offline com o fluxo de autenticação do motor.
- Tokens persistidos com AES-GCM no Android Keystore durante o build.
- Tokens redigidos do logcat.
- Mods, Java Runtime, RAM, tema, plano de fundo, arquivos e logs.
- Proteção contra `NullPointerException` nos listeners da tela antiga do Pojav.

O PojavLauncher fica em `pojav-core` como submódulo e não é apresentado como código original do Mikael Launcher.
