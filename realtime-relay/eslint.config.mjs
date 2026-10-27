import js from '@eslint/js'
import globals from 'globals'

export default [
  // k6 부하 테스트 스크립트는 Node.js가 아닌 k6 환경(ES Module)에서 실행되므로 검사에서 제외한다
  { ignores: ['load-test/**'] },
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'commonjs',
      globals: globals.node,
    },
    rules: js.configs.recommended.rules,
  },
]