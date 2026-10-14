import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import prettier from 'eslint-config-prettier'

/**
 * ESLint 扁平配置（flat config，ESLint 9 的默认形态）。
 *
 * 三个刻意的取舍：
 *
 * 1. **Vue 用 `flat/essential` 而不是 `flat/recommended`。**
 *    recommended 里塞了大量排版规则（属性换行、标签顺序……），对存量代码会刷出成百上千条
 *    噪音，真正的问题反而被淹没。essential 只保留"会出 bug 的"规则，
 *    格式这件事交给 Prettier 单一负责 —— 这也是下面要把 prettier 放在最后的原因。
 *
 * 2. **不用带类型的规则（type-aware linting）。**
 *    像 `@typescript-eslint/no-floating-promises` 需要开启 projectService、让 ESLint 读
 *    整个 tsconfig，lint 时间会明显变长。当前项目里 `vue-tsc` 已经承担了类型检查，
 *    lint 专注做类型系统覆盖不到的事（未使用变量、可疑写法）就够了。
 *
 * 3. **显式关掉 `no-undef`。**
 *    TS 文件里哪些标识符存在由编译器判断，ESLint 再判一遍只会误报（比如浏览器全局变量）。
 *    只对 TS / Vue 关，不关整个项目的。
 */
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'public/**'] },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],

  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        // .vue 里的 <script lang="ts"> 要先交给 TS 解析器
        parser: tseslint.parser,
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
  },

  {
    files: ['**/*.{ts,vue}'],
    rules: {
      // 见上文第 3 点
      'no-undef': 'off',
      // 未使用的变量留成警告而不是报错：重构中途难免有半成品，
      // 但要让它在 CI 输出里看得见。以 _ 开头的参数视为有意忽略。
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },

  // 必须放在最后：关掉所有与格式化相关的规则，避免 ESLint 和 Prettier 互相打架
  prettier,
)
