# FWLauoutCreator
在LayoutCreator的基础上修改

- 1 在Activity中不再要求必须使用在onCreate方法中的setContentView方法里面

- 2 增加了onClick可选功能

- 3 其他功能与LayoutCreator相同，可查看[LayoutCreator源码](https://github.com/ApacheJondy/BorePlugin)

- 4 在Activity中，生成的是findId()方法，findId不生成调用，需要自己手动调用。

- 5 在非Activity中，生成的是findId(View view)方法，findId不生成调用，需要自己手动调用。


