/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // 通配声明只负责让 TS 知道「*.vue 是个组件」。
  // 不要写成 DefineComponent<{}, {}, any>：那会引入 any（lint 报错）和一个含义模糊的 {} 类型。
  // 省掉泛型参数即可 —— DefineComponent 本身就带默认值，且具体 props/emits 由各组件自己的
  // defineProps / defineEmits 提供，这里不需要也不应该重新描述。
  const component: DefineComponent
  export default component
}
