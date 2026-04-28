import request from '@/utils/request'

export const chatWithAiCustomerService = (data: any) =>
  request({
    url: '/ai/customer-service/chat',
    method: 'post',
    data
  })
