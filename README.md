# Mikael Launcher V3.1

Launcher Android para Minecraft Java Edition com interface própria em Landscape/Portrait e motor baseado no PojavLauncher.

## Arquitetura

- `pojav-core/`: submódulo do PojavLauncher, mantendo o motor de Java, downloads, assets, natives, classpath, renderização, controles e execução do Minecraft.
- `mikael_overlay/`: interface e integrações do Mikael Launcher aplicadas durante o build.
- `.github/workflows/build-release.yml`: compila, executa lint, gera o APK debug e publica o artefato do build.

## Compilar

```bash
git clone --recurse-submodules https://github.com/mikael8367/Mikael-launcher-V3.1.git
cd Mikael-launcher-V3.1
bash mikael_overlay/prepare_pojav.sh
cd pojav-core
./gradlew :app_pojavlauncher:compileFullDebugJavaWithJavac
./gradlew :app_pojavlauncher:lintFullDebug
```

As alterações feitas no checkout do submódulo durante o build são temporárias; o gitlink continua apontando para o upstream fixado.

Consulte `mikael_overlay/THIRD_PARTY_NOTICES.md` para os avisos de terceiros.
