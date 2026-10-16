import type { Linter } from 'eslint';

const restrictedImportIgnores = ['**/vite.config.mts'];

const customConfig: Linter.Config[] = [
  // shadcn-ui 内部组件是自动生成的，不做太多限制
  {
    files: ['packages/@core/ui-kit/shadcn-ui/**/**'],
    rules: {
      'vue/require-default-prop': 'off',
    },
  },
  {
    files: [
      'apps/**/**',
      'packages/effects/**/**',
      'packages/utils/**/**',
      'packages/types/**/**',
      'packages/locales/**/**',
    ],
    ignores: restrictedImportIgnores,
    rules: {
      'perfectionist/sort-interfaces': 'off',
    },
  },
  {
    // apps内部的一些基础规则
    files: ['apps/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['#/api/*'],
              message:
                '不能导入 #/api 包，请直接使用 @core 包提供的能力',
            },
            {
              group: ['#/layouts/*'],
              message:
                '不能导入 #/layouts 包，请直接使用 @core 包提供的能力',
            },
            {
              group: ['#/locales/*'],
              message:
                '不能导入 #/locales 包，请直接使用 @core 包提供的能力',
            },
            {
              group: ['#/stores/*'],
              message:
                '不能导入 #/stores 包，请直接使用 @core 包提供的能力',
            },
          ],
        },
      ],
    },
  },
  {
    // @core内部组件，不能引入@vben/* 里面的包
    files: ['packages/@core/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*'],
              message:
                '@core 包不能导入 @vben 包，请直接使用 @core 包提供的能力',
            },
          ],
        },
      ],
    },
  },
  {
    // @core/shared内部组件，不能引入@vben/* 或者 @vben-core/* 里面的包
    files: ['packages/@core/base/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*', '@vben-core/*'],
              message:
                '@vben-core/shared 包不能导入 @vben 包，请直接使用 @core/shared 包提供的能力',
            },
          ],
        },
      ],
    },
  },

  {
    // 不能引入@vben/*里面的包
    files: [
      'packages/types/**/**',
      'packages/utils/**/**',
      'packages/icons/**/**',
      'packages/constants/**/**',
      'packages/styles/**/**',
      'packages/stores/**/**',
      'packages/preferences/**/**',
      'packages/locales/**/**',
    ],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*'],
              message:
                '不能导入 @vben 包，请直接使用 @core 包提供的能力',
            },
          ],
        },
      ],
    },
  },
  // 后端模拟代码，不需要太多规则
  {
    files: ['apps/backend-mock/**/**', 'docs/**/**'],
    rules: {
      'no-console': 'off',
    },
  },
  {
    files: ['**/**/playwright.config.ts'],
    rules: {
      'no-console': 'off',
    },
  },
  {
    files: ['internal/**/**', 'scripts/**/**'],
    rules: {
      'no-console': 'off',
    },
  },
  {
    files: ['packages/@core/base/shared/src/utils/inference.ts'],
    rules: {
      'vue/prefer-import-from-vue': 'off',
    },
  },
];

export { customConfig };
