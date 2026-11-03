import {request} from './client'
export interface ManagedCustomer {publicId:string;username:string;displayName:string;createdAt:string;bookingCount:number;stores:{publicId:string;storeNo:string;name:string}[]}
export interface CustomerList {items:ManagedCustomer[];total:number;page:number;size:number}
export interface CustomerDetail {customer:ManagedCustomer;bookings:{publicId:string;storeName:string;visitorName:string;phone:string;visitType:string;preferredDate:string;status:string;remark:string|null}[]}
export const managedCustomers={
 list:(q:string,page:number)=>request<CustomerList>(`/api/tenant/v1/customers?q=${encodeURIComponent(q)}&page=${page}&size=20`),
 detail:(id:string)=>request<CustomerDetail>(`/api/tenant/v1/customers/${encodeURIComponent(id)}`)
}
