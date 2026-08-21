package com.tangluobo.tomato.utils;

import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * 行选择器列（表格最左侧带三角箭头的列）的"按下并拖动选中多行"行为。
 *
 * <p>用于在行选择器单元格上按下鼠标后不释放并拖动，实现连续多行选中
 * （Excel / Windows 资源管理器行头拖拽风格）。
 *
 * <p>调用方需在自身的 MOUSE_PRESSED 处理逻辑中：
 * <ul>
 *   <li>普通点击分支：设置返回的 {@code holder[0] = 起始行索引}</li>
 *   <li>Ctrl/Shift 等修饰键分支：设置 {@code holder[0] = -1}（禁用拖拽范围选中）</li>
 * </ul>
 */
public final class RowSelectorDragSelection {

    private RowSelectorDragSelection() {
    }

    /**
     * 在行选择器列单元格上安装"按下并拖动选中多行"行为。
     *
     * @param tableView 目标表格
     * @param cell      行选择器列单元格
     * @return 长度为 1 的 int 数组，用于记录拖拽起始行索引；调用方在 MOUSE_PRESSED 时设置
     */
    public static int[] install(TableView<?> tableView, TableCell<?, ?> cell) {
        final int[] dragStart = {-1};

        cell.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            if (event.isControlDown() || event.isShiftDown()) return;
            int curRow = rowIndexOf(tableView, event);
            if (curRow < 0) {
                int last = tableView.getItems().size() - 1;
                if (last < 0) return;
                curRow = isOnHeader(tableView, event) ? 0 : last;
            }
            int min = Math.min(dragStart[0], curRow);
            int max = Math.max(dragStart[0], curRow);
            tableView.getSelectionModel().clearSelection();
            tableView.getSelectionModel().selectRange(min, max + 1);
            event.consume();
        });

        cell.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> dragStart[0] = -1);

        return dragStart;
    }

    /**
     * 根据鼠标事件位置获取对应的行索引。
     * 通过遍历 PickResult 节点链找到 TableRow/TableCell，直接获取行索引。
     */
    public static int rowIndexOf(TableView<?> tableView, MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && target != tableView) {
            if (target instanceof TableRow<?> row && !row.isEmpty()) {
                return row.getIndex();
            }
            if (target instanceof TableCell<?, ?> cell
                    && cell.getTableRow() != null && !cell.getTableRow().isEmpty()) {
                return cell.getTableRow().getIndex();
            }
            target = target.getParent();
        }
        return -1;
    }

    /**
     * 判断鼠标位置是否在表头区域（列头/表头背景）。
     */
    public static boolean isOnHeader(TableView<?> tableView, MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && target != tableView) {
            if (target.getStyleClass().contains("column-header")
                    || target.getStyleClass().contains("column-header-background")
                    || target.getStyleClass().contains("nested-column-header")) {
                return true;
            }
            target = target.getParent();
        }
        return false;
    }
}
