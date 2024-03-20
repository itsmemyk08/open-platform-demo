/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.sale.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Query;

import com.axelor.common.ObjectUtils;
import com.axelor.db.EntityHelper;
import com.axelor.db.JpaSupport;
import com.axelor.db.mapper.Mapper;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Resource;
import com.axelor.sale.db.Order;
import com.axelor.sale.db.OrderLine;
import com.axelor.sale.db.OrderStatus;
import com.axelor.sale.db.Product;
import com.axelor.sale.db.repo.OrderLineRepository;
import com.axelor.sale.db.repo.OrderRepository;
import com.axelor.sale.db.repo.ProductRepository;
import com.axelor.sale.service.SaleOrderService;
import com.google.common.collect.Lists;
import com.google.inject.persist.Transactional;

public class SaleOrderController extends JpaSupport {

	@Inject
	private SaleOrderService service;

	// Item helpers
	private OrderLine findOrderLine(Map<String, Object> item) {
		int id = item.get("id") != null ? (int) item.get("id") : 0;
		if (id > 0) {
			OrderLine line = Beans.get(OrderLineRepository.class).find((long) id);
			return line;
		}
		return null;
	}

	private String getItemProductUpdateType(Map<String, Object> item) {
		String updateType = "";

		Map<String, Object> product = ((Map<String, Object>) item.get("product"));
		if (product != null) {
			int productId = (int) product.get("id");
			Product lineProduct = Beans.get(ProductRepository.class).find((long) productId);

			if (lineProduct != null && lineProduct.getUpdateType() != null && !lineProduct.getUpdateType().isEmpty()) {
				updateType = lineProduct.getUpdateType();
			}
		}

		return updateType;
	}

	private int getItemQuantity(Map<String, Object> item) {
		if (item.get("quantity") != null) {
			return (int) item.get("quantity");
		}
		OrderLine line = findOrderLine(item);
		return line != null ? line.getQuantity() : 0;
	}

	private boolean hasItemChanged(Map<String, Object> item) {
		return item.get("_changed") != null && item.get("_changed").toString().equals("true");
	}

	private void resetItem(Map<String, Object> item) {
		if (item.get("_changed") != null) {
			item.remove("_changed");
		}
	}

	private Object getItemOriginalField(Map<String, Object> item, String key) {
		Map<String, Object> original = (Map<String, Object>) item.get("_original");
		if (original != null) {
			return original.get(key);
		}
		return item.get(key);
	}

	private float getItemQtyChangedRatio(Map<String, Object> item) {
		Object oldQty = getItemOriginalField(item, "quantity");
		Object qty = item.get("quantity");

		if (oldQty != null && qty != null) {
			int _q = (int) oldQty;
			int q = (int) qty;
			return (float) q / _q;
		}

		return 1;
	}
	// Items helpers

	private List<Object> getItems(Map<String, Object> item) {
		List<Object> subItems = new ArrayList<Object>();

		try {
			subItems = (List<Object>) item.get("items");
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (subItems == null) {
			subItems = new ArrayList<Object>();

			OrderLine orderLine = findOrderLine(item);

			if (orderLine != null && orderLine.getItems() != null) {
				for (OrderLine subLine : orderLine.getItems()) {
					Product product = subLine.getProduct();

					subItems.add(new HashMap<String, Object>() {
						{
							put("id", Integer.parseInt(subLine.getId().toString()));
							put("version", subLine.getVersion());
							put("price", subLine.getPrice());
							if (product != null) {
								put("product", Resource.toMapCompact(product));
							}
						}
					});
				}

			}

			item.put("items", subItems);
		} else {
			for (Object _subItem : subItems) {
				Map<String, Object> subItem = (Map<String, Object>) _subItem;
				OrderLine line = findOrderLine(subItem);
				if (line != null) {
					if (!subItem.containsKey("price")) {
						subItem.put("price", line.getPrice());
					}
					if (!subItem.containsKey("product")) {
						subItem.put("product", Resource.toMapCompact(line.getProduct()));
					}
				}
			}
		}

		return subItems;
	}

	private ArrayList<Map<String, Object>> flattenItems(Map<String, Object> item) {
		ArrayList<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		list.add(item);

		ArrayList<Map<String, Object>> subItems = (ArrayList<Map<String, Object>>) item.get("items");

		if (subItems != null) {
			for (Map<String, Object> subItem : subItems) {
				list.addAll(flattenItems(subItem));
			}
		}
		return list;
	}

	// Quantity updaters

	private int updateQuantityByParent(Map<String, Object> item) {
		if (!hasItemChanged(item)) {
			int totalQty = 0;
			ArrayList<Map<String, Object>> subItems = (ArrayList<Map<String, Object>>) item.get("items");
			if (subItems != null && subItems.size() > 0) {
				boolean anySubItemChanged = subItems.stream().anyMatch(_item -> hasItemChanged(_item));
				for (Map<String, Object> subItem : subItems) {
					int qty = anySubItemChanged ? getItemQuantity(subItem) : updateQuantityByParent(subItem);
					totalQty += qty;

					subItem.put("quantity", qty);
					resetItem(subItem);
				}
			} else {
				totalQty = getItemQuantity(item);
			}
			item.put("quantity", totalQty);
			return totalQty;
		}
		resetItem(item);
		return (int) item.get("quantity");
	}

	private void updateQuantityByChildren(Map<String, Object> item, float ratio) {
		for (Object _subItem : getItems(item)) {
			Map<String, Object> subItem = (Map<String, Object>) _subItem;
			subItem.put("version", subItem.get("version"));
			subItem.put("quantity", Math.round(getItemQuantity(subItem) * ratio));
			updateQuantityByChildren(subItem, ratio);
		}
	}

	@Transactional
	public void computeItems(ActionRequest request, ActionResponse response) {
		Map<String, Object> context = request.getRawContext();
		Map<String, Object> parentLine = null;
		Map<String, Object> updatedLine = null;

		List<Map<String, Object>> mainItems = (List<Map<String, Object>>) context.get("items");

		if (mainItems == null || mainItems.size() == 0)
			return;

		for (Map<String, Object> item : mainItems) {
			ArrayList<Map<String, Object>> flatList = flattenItems(item);
			for (Map<String, Object> _item : flatList) {
				if (hasItemChanged(_item)) {
					parentLine = item;
					updatedLine = _item;
					break;
				}
			}
		}

		if (updatedLine == null)
			return;

		String lineUpdateType = getItemProductUpdateType(updatedLine);

		switch (lineUpdateType) {
		case "parent":
			updateQuantityByParent(parentLine);
			break;
		case "children":
			updateQuantityByChildren(updatedLine, getItemQtyChangedRatio(updatedLine));
			break;
		}

		response.setValue("items", mainItems);
	}

	public void onConfirm(ActionRequest request, ActionResponse response) {

		Order order = request.getContext().asType(Order.class);

		response.setReadonly("orderDate", order.getConfirmed());
		response.setReadonly("confirmDate", order.getConfirmed());

		if (order.getConfirmed() == Boolean.TRUE && order.getConfirmDate() == null) {
			response.setValue("confirmDate", LocalDate.now());
		}

		if (order.getConfirmed() == Boolean.TRUE) {
			response.setValue("status", OrderStatus.OPEN);
		} else if (order.getStatus() == OrderStatus.OPEN) {
			response.setValue("status", OrderStatus.DRAFT);
		}
	}

	public void calculate(ActionRequest request, ActionResponse response) {

		Order order = request.getContext().asType(Order.class);
		order = service.calculate(order);

		response.setValue("amount", order.getAmount());
		response.setValue("taxAmount", order.getTaxAmount());
		response.setValue("totalAmount", order.getTotalAmount());
	}

	public void reportToday(ActionRequest request, ActionResponse response) {
		EntityManager em = getEntityManager();
		Query q1 = em.createQuery("SELECT SUM(self.totalAmount) FROM Order AS self "
				+ "WHERE YEAR(self.orderDate) = YEAR(current_date) AND "
				+ "MONTH(self.orderDate) = MONTH(current_date) AND " + "DAY(self.orderDate) = DAY(current_date) - 1");

		Query q2 = em.createQuery("SELECT SUM(self.totalAmount) FROM Order AS self "
				+ "WHERE YEAR(self.orderDate) = YEAR(current_date) AND "
				+ "MONTH(self.orderDate) = MONTH(current_date) AND " + "DAY(self.orderDate) = DAY(current_date)");

		List<?> r1 = q1.getResultList();
		BigDecimal last = r1.get(0) == null ? BigDecimal.ZERO : (BigDecimal) r1.get(0);

		List<?> r2 = q2.getResultList();
		BigDecimal total = r2.get(0) == null ? BigDecimal.ZERO : (BigDecimal) r2.get(0);

		BigDecimal percent = BigDecimal.ZERO;
		if (total.compareTo(BigDecimal.ZERO) == 1) {
			percent = total.subtract(last).multiply(new BigDecimal(100)).divide(total, RoundingMode.HALF_UP);
		}

		Map<String, Object> data = new HashMap<>();
		data.put("total", total);
		data.put("percent", percent);
		data.put("down", total.compareTo(last) == -1);

		response.setData(Lists.newArrayList(data));
	}

	public void reportMonthly(ActionRequest request, ActionResponse response) {
		EntityManager em = getEntityManager();
		Query q1 = em.createQuery("SELECT SUM(self.totalAmount) FROM Order AS self "
				+ "WHERE YEAR(self.orderDate) = YEAR(current_date) AND "
				+ "MONTH(self.orderDate) = MONTH(current_date) - 1");

		Query q2 = em.createQuery("SELECT SUM(self.totalAmount) FROM Order AS self "
				+ "WHERE YEAR(self.orderDate) = YEAR(current_date) AND "
				+ "MONTH(self.orderDate) = MONTH(current_date)");

		List<?> r1 = q1.getResultList();
		BigDecimal last = r1.get(0) == null ? BigDecimal.ZERO : (BigDecimal) r1.get(0);

		List<?> r2 = q2.getResultList();
		BigDecimal total = r2.get(0) == null ? BigDecimal.ZERO : (BigDecimal) r2.get(0);

		BigDecimal percent = BigDecimal.ZERO;
		if (total.compareTo(BigDecimal.ZERO) == 1) {
			percent = total.subtract(last).divide(total, 4, RoundingMode.HALF_UP);
		}

		Map<String, Object> data = new HashMap<>();
		data.put("total", total);
		data.put("percent", percent);
		data.put("up", total.compareTo(last) > 0);
		data.put("tag", I18n.get("Monthly"));
		data.put("tagCss", "label-success");

		response.setData(Lists.newArrayList(data));
	}

	public void showTotalSales(ActionRequest request, ActionResponse response) {
		List<Map<String, Object>> data = (List<Map<String, Object>>) request.getRawContext().get("_data");
		if (ObjectUtils.isEmpty(data)) {
			response.setNotify(I18n.get("No sales"));
			return;
		}
		BigDecimal totalAmount = data.stream().map(i -> i.get("amount").toString()).map(BigDecimal::new)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		response.setNotify(String.format("%s : %s", I18n.get("Total sales"), totalAmount));
	}

	public void showCustomerSales(ActionRequest request, ActionResponse response) {
		Object data = request.getRawContext().get("customerId");
		if (ObjectUtils.isEmpty(data)) {
			return;
		}

		ActionView.ActionViewBuilder builder = ActionView.define("Customer sales").model(Order.class.getName());
		builder.domain("self.customer.id = " + data);
		response.setView(builder.map());
	}
}
