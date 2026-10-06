import { useEffect, useState, useSyncExternalStore } from 'react';
import { Button, Card, Form, Input, Select, Space, Table } from 'antd';
import { ReadController, type RequestFilter, type RequestReader, type RequestSummary } from '../../shared/workflow';
import { NoRecords, ReadPanel } from '../../shared/WorkflowElements';

const initialFilter: RequestFilter = { keyword: '', date: '', status: '', source: '', page: 1 };
export function RequestList({ reader, onSelect, onManual }: { reader: RequestReader; onSelect: (record: RequestSummary) => void; onManual?: () => void }) {
  const [form] = Form.useForm<RequestFilter>();
  const [filter, setFilter] = useState(initialFilter);
  const [controller] = useState(() => new ReadController(reader.search.bind(reader)));
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  useEffect(() => { void controller.run(initialFilter); return controller.stop; }, [controller]);
  const search = (next: RequestFilter) => { setFilter(next); void controller.run(next); };
  return <Card title="申请单列表" extra={onManual && <Button type="primary" onClick={onManual}>手工申请</Button>}>
    <Form form={form} initialValues={initialFilter} layout="vertical" className="search-form"
      onFinish={values => search({ ...values, page: 1 })}>
      <Form.Item name="keyword" label="申请号 / 就诊号 / 患者双标识"><Input maxLength={255} /></Form.Item>
      <Form.Item name="date" label="申请日期"><Input type="date" /></Form.Item>
      <Form.Item name="status" label="状态"><Select options={[
        { value: '', label: '全部' }, { value: '草稿', label: '草稿' }, { value: '待接收', label: '待接收' },
        ...['接收异常', '已退回', '已接收'].map(value => ({ value, label: value })),
      ]} /></Form.Item>
      <Form.Item label="来源"><Input disabled value="由后台配置自动确定" /></Form.Item>
      <Space><Button htmlType="submit" type="primary" disabled={state.status === 'loading'}>查询</Button>
        <Button onClick={() => { form.resetFields(); search(initialFilter); }}>重置</Button>
        <Button onClick={() => void controller.run(filter)} disabled={state.status === 'loading'}>刷新</Button></Space>
    </Form>
    <ReadPanel state={state}>{data => data.items.length === 0 ? <NoRecords /> :
      <Table<RequestSummary> rowKey="id" onRow={row => ({ 'aria-label': '申请 ' + row.requestNumber, 'data-testid': 'request-row-' + row.id })} dataSource={data.items} scroll={{ x: 850 }}
        pagination={{ current: data.page, pageSize: data.pageSize, total: data.total, showSizeChanger: false,
          onChange: page => search({ ...filter, page }) }} columns={[
          { title: '申请号', dataIndex: 'requestNumber' },
          { title: '患者 / 就诊', key: 'patient', render: (_, row) => <>{row.patientLabel}<br />{row.encounterNumber}</> },
          { title: '送检部位', dataIndex: 'site' }, { title: '送检科室', dataIndex: 'department' },
          { title: '申请时间', dataIndex: 'requestedAt' }, { title: '状态', dataIndex: 'statusLabel' },
          { title: '操作', key: 'action', render: (_, row) => <Button onClick={() => onSelect(row)}>查看 {row.requestNumber}</Button> },
        ]} />}</ReadPanel>
  </Card>;
}
