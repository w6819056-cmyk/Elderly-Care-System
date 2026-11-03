import { request } from './client'
export interface Store { publicId: string; storeNo: string; name: string; status: string; version: number; operational: boolean }
export interface CreateStore { storeNo: string; name: string; regionCode: string; address: string }
export const organizationApi = {
 stores: () => request<Store[]>('/api/tenant/v1/hq/stores'),
 create: (body: CreateStore) => request<Store>('/api/tenant/v1/hq/stores', {method:'POST',body:JSON.stringify(body)}),
 status: (id:string,status:string) => request<Store>(`/api/tenant/v1/hq/stores/${encodeURIComponent(id)}/status`,{method:'PATCH',body:JSON.stringify({status})})
}
