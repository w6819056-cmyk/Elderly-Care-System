import { request } from './client'
export interface Staff {id:number;publicId:string;username:string;displayName:string;staffType:string;homeStoreId:number|null;status:string;phone?:string;email?:string;hasAvatar?:boolean}
export interface Role {publicId:string;roleCode:string;name:string;roleDomain:string;protectedRole:boolean;status:string}
export interface NewStaff {username:string;password:string;displayName:string;phone:string;staffType:string;homeStorePublicId:string|null;roleCodes:string[];email:string;avatarDataUrl:string|null}
export const iamApi={staff:()=>request<Staff[]>('/api/tenant/v1/hq/staff'),roles:()=>request<Role[]>('/api/tenant/v1/hq/roles'),create:(body:NewStaff)=>request<Staff>('/api/tenant/v1/hq/staff',{method:'POST',body:JSON.stringify(body)}),disable:(id:string)=>request<void>(`/api/tenant/v1/hq/staff/${encodeURIComponent(id)}/disable`,{method:'PATCH'})}

export interface StaffAccess {roleCodes:string[];protectedOwner:boolean;scopeType:string;storePublicIds:string[];storeGroupPublicIds:string[]}
export const staffAccessApi={get:(id:string)=>request<StaffAccess>(`/api/tenant/v1/hq/staff/${encodeURIComponent(id)}/access`),roles:(id:string,roleCodes:string[])=>request(`/api/tenant/v1/hq/staff/${encodeURIComponent(id)}/roles`,{method:'PATCH',body:JSON.stringify({roleCodes})}),scope:(id:string,body:unknown)=>request(`/api/tenant/v1/hq/staff/${encodeURIComponent(id)}/data-scope`,{method:'PUT',body:JSON.stringify(body)})}
