// 数据集 Mock 数据

export const mockDatasets = [
  {
    id: 'ds-001',
    name: '客服常见问题集V1',
    type: 'text_qa',
    hasExpectedResult: true,
    itemCount: 8,
    createdAt: '2026-03-15 10:00:00',
    updatedAt: '2026-03-18 15:30:00',
    description: '客服场景常见问题，包含标准答案',
    items: [
      { id: 'q-001', question: '如何修改登录密码？', images: [], expectedAnswer: '进入「个人设置」页面，点击「安全设置」，在密码修改区域输入旧密码和新密码，点击确认即可完成密码修改。' },
      { id: 'q-002', question: '如何申请退款？', images: [], expectedAnswer: '进入「我的订单」页面，找到需要退款的订单，点击「申请退款」按钮，填写退款原因，提交后等待审核。一般1-3个工作日内处理完成。' },
      { id: 'q-003', question: '忘记密码怎么办？', images: [], expectedAnswer: '在登录页面点击「忘记密码」，输入注册手机号或邮箱，通过验证码验证身份后，设置新密码即可。' },
      { id: 'q-004', question: '如何查看物流信息？', images: [], expectedAnswer: '进入「我的订单」页面，点击对应订单的「查看物流」按钮，即可查看实时物流跟踪信息。' },
      { id: 'q-005', question: '会员等级如何提升？', images: [], expectedAnswer: '会员等级根据累计消费金额自动提升。银卡会员累计消费满1000元，金卡会员累计消费满5000元，钻石会员累计消费满20000元。' },
      { id: 'q-006', question: '如何联系客服？', images: [], expectedAnswer: '点击页面右下角的「在线客服」悬浮按钮，即可进入实时对话窗口。服务时间为每天9:00-22:00。' },
      { id: 'q-007', question: '如何修改收货地址？', images: [], expectedAnswer: '进入「个人设置」->「收货地址管理」，可以新增、编辑或删除收货地址。订单未发货前也可在订单详情中修改收货地址。' },
      { id: 'q-008', question: '优惠券如何使用？', images: [], expectedAnswer: '在结算页面，选择可用优惠券即可自动抵扣。优惠券有使用条件和有效期限，请在有效期内使用。' }
    ]
  },
  {
    id: 'ds-002',
    name: '产品识别测试集',
    type: 'image_text',
    hasExpectedResult: false,
    itemCount: 5,
    createdAt: '2026-03-18 14:00:00',
    updatedAt: '2026-03-18 14:00:00',
    description: '产品图片识别能力测试，无标准答案需人工评判',
    items: [
      { id: 'q-101', question: '请描述这张图片中的产品', images: ['/mock-images/placeholder.svg'], expectedAnswer: null },
      { id: 'q-102', question: '这个产品的主要特点是什么？', images: ['/mock-images/placeholder.svg'], expectedAnswer: null },
      { id: 'q-103', question: '请对比这两张图片中的产品差异', images: ['/mock-images/placeholder.svg', '/mock-images/placeholder.svg'], expectedAnswer: null },
      { id: 'q-104', question: '这张图片中包含哪些品牌？', images: ['/mock-images/placeholder.svg'], expectedAnswer: null },
      { id: 'q-105', question: '请估算这个产品的价格区间', images: ['/mock-images/placeholder.svg'], expectedAnswer: null }
    ]
  },
  {
    id: 'ds-003',
    name: '文档理解评测集',
    type: 'image_text_expected',
    hasExpectedResult: true,
    itemCount: 6,
    createdAt: '2026-03-20 09:00:00',
    updatedAt: '2026-03-22 11:00:00',
    description: '文档图片+问题+标准答案，测试文档理解能力',
    items: [
      { id: 'q-201', question: '这份报表中2025年Q4的总收入是多少？', images: ['/mock-images/placeholder.svg'], expectedAnswer: '2025年Q4总收入为1,285万元，同比增长12.3%。' },
      { id: 'q-202', question: '请提取这份合同中的关键条款', images: ['/mock-images/placeholder.svg'], expectedAnswer: '合同期限自2026年1月1日至2026年12月31日，付款方式为月付，月付款金额暂付30万元，违约金合同总金额的5%。' },
      { id: 'q-203', question: '这张流程图描述的是什么业务流程？', images: ['/mock-images/placeholder.svg'], expectedAnswer: '该流程图描述的是订单审批流程，包括：提交申请->部门审核->财务审核->总经理审批->执行，每个环节都有驳回通道。' },
      { id: 'q-204', question: '表格中销量最高的产品是哪个？', images: ['/mock-images/placeholder.svg'], expectedAnswer: '销量最高的产品是「智能手环Pro」，月销量达15,680件，占总销量的28.5%。' },
      { id: 'q-205', question: '这份简历中候选人有几年工作经验？', images: ['/mock-images/placeholder.svg'], expectedAnswer: '候选人有8年工作经验，其中5年在互联网行业，3年在金融科技行业。' },
      { id: 'q-206', question: '发票上的金额和日期分别是什么？', images: ['/mock-images/placeholder.svg'], expectedAnswer: '发票金额：¥23,450.00（含税），开票日期：2026年3月15日。' }
    ]
  },
  {
    id: 'ds-004',
    name: '通用对话能力测试',
    type: 'text_qa',
    hasExpectedResult: false,
    itemCount: 6,
    createdAt: '2026-03-22 16:00:00',
    updatedAt: '2026-03-22 16:00:00',
    description: '通用对话场景，无标准答案，需人工对比评判',
    items: [
      { id: 'q-301', question: '请用简单的语言解释什么是量子计算', images: [], expectedAnswer: null },
      { id: 'q-302', question: '如何有效管理一个远程团队？', images: [], expectedAnswer: null },
      { id: 'q-303', question: '写一首关于春天的短诗', images: [], expectedAnswer: null },
      { id: 'q-304', question: '分析一下人工智能对教育行业的影响', images: [], expectedAnswer: null },
      { id: 'q-305', question: '设计一个简单的待办事项应用的技术方案', images: [], expectedAnswer: null },
      { id: 'q-306', question: '如何在面试中展示自己的领导力？', images: [], expectedAnswer: null }
    ]
  }
]

