# python.jar —— 万物皆可 jar 0n1p1
> Python是很牛逼的语言，而它却不支持 Java 
> 为什么不扩大java的市场呢？
>于是我发明了——Pyjar 


## 它是什么

`python.jar` 是一个自包含的 Python 运行时。**它内置了一整份独立 CPython 3.12**

```bash
java -jar python.jar --version
java -jar python.jar main.py                 # 跑脚本, 参数原样透传
java -jar python.jar -m pip install requests # 装包
java -jar python.jar                         # 进入交互式 REPL
```

## 包放哪里

**jar 同级的 `site-packages/` 文件夹**




## 内部构造

```
python.jar (zip)
├── PyJar.class                        
├── pyjar/runtime-version.txt          
└── runtimes/windows-x64/              
    ├── python.exe
    ├── python312.dll
    ├── Lib/  DLLs/  ...
```


## 构建(Java + Gradle)

```powershell
.\gradlew.bat jar        
```

任务链(`gradlew.bat tasks` 可查看全部):




## 已知取舍 / 免责声明

- 运行时按 jar 打包,发行体积 ≈ 一个精简 CPython(44.7 MB),换来"任意 JVM 机器即插即用"
- 本 PoC 只内置 windows-x64 运行时,在别的平台会给出友好报错并列出已内置平台
